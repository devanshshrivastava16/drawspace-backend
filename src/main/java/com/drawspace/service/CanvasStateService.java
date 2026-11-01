package com.drawspace.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.drawspace.domain.EventType;
import com.drawspace.domain.ToolType;
import com.drawspace.entity.CanvasSnapshot;
import com.drawspace.entity.DrawingEvent;
import com.drawspace.repository.CanvasSnapshotRepository;
import com.drawspace.repository.DrawingEventRepository;

/**
 * Canonical server-side canvas state and deterministic replay (CONTRACT §8).
 *
 * <p>{@link CanvasState} mirrors the JSON model with String-keyed maps so that JSONB
 * serialization ({@link #toSnapshotData}) and deserialization ({@link #applySnapshotData})
 * are trivial. {@link #applyOp} implements §8 exactly — it MUST stay semantically
 * identical to the frontend's client-side replay.</p>
 *
 * <p>State is rebuilt from PostgreSQL: latest {@code canvas_snapshots} row (§9) then
 * every {@code drawing_events} row with a higher sequence number, applied strictly in
 * ascending sequence order. A small LRU cache (max 100 drawings) keeps live ops applied
 * incrementally via {@link #applyLive}; on any sequence-order anomaly the cache is
 * discarded and rebuilt from the database (source of truth).</p>
 *
 * <p>Known limitation (inherent to §9's snapshot shape): ledger details (prior geometry,
 * priors, cleared ids) only exist for events AFTER the latest snapshot, so an UNDO whose
 * target event predates the latest snapshot degrades to a graceful no-op during replay
 * from that snapshot — identical behavior on both sides.</p>
 */
@Service
public class CanvasStateService {

    private static final Logger log = LoggerFactory.getLogger(CanvasStateService.class);

    private static final int MAX_CACHED_DRAWINGS = 100;
    private static final String DEFAULT_CANVAS_BG = "#FFFFFF";

    private static final Set<ToolType> SHAPE_TOOLS = Set.of(
            ToolType.LINE, ToolType.ARROW, ToolType.RECTANGLE, ToolType.ROUNDED_RECTANGLE,
            ToolType.CIRCLE, ToolType.ELLIPSE, ToolType.TRIANGLE, ToolType.POLYGON,
            ToolType.STAR, ToolType.HEART, ToolType.SPEECH_BUBBLE);

    private final CanvasSnapshotRepository canvasSnapshotRepository;
    private final DrawingEventRepository drawingEventRepository;

    /** Access-order LRU cache of per-drawing canonical state. */
    private final Map<UUID, CanvasState> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, CanvasState> eldest) {
                    return size() > MAX_CACHED_DRAWINGS;
                }
            });

    public CanvasStateService(CanvasSnapshotRepository canvasSnapshotRepository,
                              DrawingEventRepository drawingEventRepository) {
        this.canvasSnapshotRepository = canvasSnapshotRepository;
        this.drawingEventRepository = drawingEventRepository;
    }

    // ------------------------------------------------------------------
    // State container (§8)
    // ------------------------------------------------------------------

    /**
     * Canonical canvas state: layers and objects as String-keyed property maps,
     * plus the undo/redo ledgers. Mirrors the JSON model 1:1.
     */
    public static class CanvasState {

        private volatile String canvasBg = DEFAULT_CANVAS_BG;
        /** layerId → {layerId, name, order, visible, locked, deleted}. */
        private final Map<String, Map<String, Object>> layers = new LinkedHashMap<>();
        /** objectId → {objectId, layerId, type, tool, color, ..., deleted}. */
        private final Map<String, Map<String, Object>> objects = new LinkedHashMap<>();
        /** eventId → objectId for add ops. */
        private final Map<String, String> objectIdByEventId = new HashMap<>();
        /** eventId → {x,y,scale,rotation} BEFORE an OBJECT_TRANSFORM. */
        private final Map<String, Map<String, Object>> priorGeometryByEventId = new HashMap<>();
        /** eventId → full layer props before LAYER_UPDATE/LAYER_DELETE, or {layerId → order} before LAYER_REORDER. */
        private final Map<String, Map<String, Object>> priorLayerByEventId = new HashMap<>();
        /** eventId → canvasBg before a CANVAS_BG. */
        private final Map<String, String> priorCanvasBgByEventId = new HashMap<>();
        /** eventId → deleted flag before an OBJECT_DELETE. */
        private final Map<String, Boolean> priorDeletedByEventId = new HashMap<>();
        /** undoEventId → info to re-apply on REDO. */
        private final Map<String, Map<String, Object>> redoInfoByUndoEventId = new HashMap<>();
        /** CLEAR eventId → objectIds that were visible before the clear. */
        private final Map<String, List<String>> clearedByEventId = new HashMap<>();
        /** eventId → the raw op map, so UNDO can resolve its target's type. */
        private final Map<String, Map<String, Object>> opsByEventId = new HashMap<>();
        /** Highest sequence number applied to this state (snapshot base or replayed event). */
        private volatile long lastAppliedSequence;

        public String getCanvasBg() {
            return canvasBg;
        }

        public void setCanvasBg(String canvasBg) {
            this.canvasBg = canvasBg;
        }

        public Map<String, Map<String, Object>> getLayers() {
            return layers;
        }

        public Map<String, Map<String, Object>> getObjects() {
            return objects;
        }

        public long getLastAppliedSequence() {
            return lastAppliedSequence;
        }

        public void setLastAppliedSequence(long lastAppliedSequence) {
            this.lastAppliedSequence = lastAppliedSequence;
        }
    }

    /** @return a fresh, empty canonical state. */
    public CanvasState freshState() {
        return new CanvasState();
    }

    // ------------------------------------------------------------------
    // Replay
    // ------------------------------------------------------------------

    /**
     * Rebuilds the canonical state for a drawing from PostgreSQL: latest snapshot (§9)
     * then events with a higher sequence number, ascending. Refreshes the LRU cache.
     */
    public CanvasState buildState(UUID drawingId) {
        CanvasState state = new CanvasState();
        long fromSequence = 0;
        Optional<CanvasSnapshot> latest = canvasSnapshotRepository.findTopByDrawingIdOrderBySequenceNumberDesc(drawingId);
        if (latest.isPresent()) {
            applySnapshotData(state, latest.get().getSnapshotData());
            fromSequence = latest.get().getSequenceNumber();
        }
        List<DrawingEvent> events = drawingEventRepository
                .findByDrawingIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(drawingId, fromSequence, Pageable.unpaged());
        long last = fromSequence;
        for (DrawingEvent event : events) {
            Map<String, Object> op = event.getEventData() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(event.getEventData());
            op.putIfAbsent("eventId", event.getEventId().toString());
            op.putIfAbsent("type", event.getEventType());
            applyOp(state, op);
            last = Math.max(last, event.getSequenceNumber());
        }
        state.setLastAppliedSequence(last);
        cache.put(drawingId, state);
        return state;
    }

    /**
     * Applies one committed op to the cached state incrementally (after-commit path of
     * the save pipeline). If the op is out of order (or the cache is stale), the state
     * is rebuilt from the database to guarantee convergence.
     */
    public void applyLive(UUID drawingId, long sequence, Map<String, Object> opMap) {
        CanvasState state = cache.get(drawingId);
        if (state == null) {
            state = buildState(drawingId);
        }
        synchronized (state) {
            if (sequence <= state.getLastAppliedSequence()) {
                log.debug("Live op sequence {} not ahead of cached state {} for drawing {} — rebuilding",
                        sequence, state.getLastAppliedSequence(), drawingId);
                buildState(drawingId);
                return;
            }
            applyOp(state, opMap);
            state.setLastAppliedSequence(sequence);
        }
    }

    /**
     * Canonical applyOp per CONTRACT §8. Unknown object/layer ids and unknown event
     * types are graceful no-ops; replay = applying ops strictly in sequenceNumber order.
     */
    public void applyOp(CanvasState state, Map<String, Object> op) {
        if (state == null || op == null) {
            return;
        }
        String eventId = str(op, "eventId");
        String typeName = str(op, "type");
        if (eventId == null || typeName == null) {
            return;
        }
        EventType type;
        try {
            type = EventType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            log.debug("Replay skipped unknown event type {}", typeName);
            return;
        }
        state.opsByEventId.put(eventId, op);
        switch (type) {
            case BRUSH_STROKE, SHAPE, TEXT, STICKER, OBJECT_DUPLICATE -> applyAdd(state, eventId, op, type);
            case OBJECT_TRANSFORM -> applyTransform(state, eventId, op);
            case OBJECT_DELETE -> applyObjectDelete(state, eventId, op);
            case LAYER_CREATE -> applyLayerCreate(state, op);
            case LAYER_UPDATE -> applyLayerUpdate(state, eventId, op);
            case LAYER_REORDER -> applyLayerReorder(state, eventId, op);
            case LAYER_DELETE -> applyLayerDelete(state, eventId, op);
            case CANVAS_BG -> applyCanvasBg(state, eventId, op);
            case CLEAR -> applyClear(state, eventId);
            case UNDO -> applyUndo(state, eventId, op);
            case REDO -> applyRedo(state, op);
        }
    }

    // ------------------------------------------------------------------
    // §8 primitives
    // ------------------------------------------------------------------

    private void applyAdd(CanvasState state, String eventId, Map<String, Object> op, EventType type) {
        String objectId = str(op, "objectId");
        if (objectId == null || objectId.isBlank()) {
            objectId = "op-" + eventId; // deterministic fallback keeps replay reproducible
        }
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("objectId", objectId);
        putIfNotNull(obj, "layerId", str(op, "layerId"));
        obj.put("type", objectTypeName(type, str(op, "tool")));
        putIfNotNull(obj, "tool", str(op, "tool"));
        putIfNotNull(obj, "color", str(op, "color"));
        putIfNotNull(obj, "fillColor", str(op, "fillColor"));
        putIfNotNull(obj, "brushSize", integer(op, "brushSize"));
        putIfNotNull(obj, "opacity", dbl(op, "opacity"));
        putIfNotNull(obj, "lineStyle", str(op, "lineStyle"));
        if (op.get("points") instanceof List<?> points) {
            obj.put("points", points);
        }
        putIfNotNull(obj, "text", str(op, "text"));
        putIfNotNull(obj, "font", str(op, "font"));
        putIfNotNull(obj, "fontSize", integer(op, "fontSize"));
        putIfNotNull(obj, "bold", bool(op, "bold"));
        putIfNotNull(obj, "italic", bool(op, "italic"));
        putIfNotNull(obj, "align", str(op, "align"));
        putIfNotNull(obj, "bgColor", str(op, "bgColor"));
        putIfNotNull(obj, "sticker", str(op, "sticker"));
        putIfNotNull(obj, "x", dbl(op, "x"));
        putIfNotNull(obj, "y", dbl(op, "y"));
        putIfNotNull(obj, "width", dbl(op, "width"));
        putIfNotNull(obj, "height", dbl(op, "height"));
        putIfNotNull(obj, "sides", integer(op, "sides"));
        putIfNotNull(obj, "scale", dbl(op, "scale"));
        putIfNotNull(obj, "rotation", dbl(op, "rotation"));
        obj.put("deleted", false);
        state.objects.put(objectId, obj);
        state.objectIdByEventId.put(eventId, objectId);
    }

    private void applyTransform(CanvasState state, String eventId, Map<String, Object> op) {
        String objectId = str(op, "objectId");
        Map<String, Object> obj = objectId == null ? null : state.objects.get(objectId);
        if (obj == null) {
            return; // unknown object → graceful no-op
        }
        // record prior absolute geometry (§8) for UNDO inversion
        state.priorGeometryByEventId.put(eventId, geometryOf(obj));
        if (op.get("transform") instanceof Map<?, ?> rawTransform) {
            @SuppressWarnings("unchecked")
            Map<String, Object> transform = (Map<String, Object>) rawTransform;
            applyGeometry(obj, transform);
        } else {
            // fallback: absolute x/y/scale/rotation sent as top-level op fields
            applyGeometry(obj, op);
        }
    }

    private void applyObjectDelete(CanvasState state, String eventId, Map<String, Object> op) {
        String objectId = str(op, "objectId");
        Map<String, Object> obj = objectId == null ? null : state.objects.get(objectId);
        if (obj == null) {
            return;
        }
        state.priorDeletedByEventId.put(eventId, Boolean.TRUE.equals(obj.get("deleted")));
        obj.put("deleted", true);
    }

    private void applyLayerCreate(CanvasState state, Map<String, Object> op) {
        String layerId = str(op, "layerId");
        if (layerId == null || state.layers.containsKey(layerId)) {
            return;
        }
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("layerId", layerId);
        String name = str(op, "name");
        layer.put("name", name != null && !name.isBlank() ? name : "Layer " + (state.layers.size() + 1));
        layer.put("order", state.layers.size());
        Boolean visible = bool(op, "visible");
        layer.put("visible", visible == null || visible);
        layer.put("locked", Boolean.TRUE.equals(bool(op, "locked")));
        layer.put("deleted", false);
        state.layers.put(layerId, layer);
    }

    private void applyLayerUpdate(CanvasState state, String eventId, Map<String, Object> op) {
        Map<String, Object> layer = state.layers.get(str(op, "layerId"));
        if (layer == null) {
            return;
        }
        state.priorLayerByEventId.put(eventId, new LinkedHashMap<>(layer));
        String name = str(op, "name");
        if (name != null) {
            layer.put("name", name);
        }
        Boolean visible = bool(op, "visible");
        if (visible != null) {
            layer.put("visible", visible);
        }
        Boolean locked = bool(op, "locked");
        if (locked != null) {
            layer.put("locked", locked);
        }
    }

    private void applyLayerReorder(CanvasState state, String eventId, Map<String, Object> op) {
        Map<String, Object> priorOrders = new LinkedHashMap<>();
        state.layers.forEach((layerId, layer) -> priorOrders.put(layerId, layer.get("order")));
        state.priorLayerByEventId.put(eventId, priorOrders);
        List<String> order = stringList(op, "order");
        if (order == null) {
            return;
        }
        int index = 0;
        for (String layerId : order) {
            Map<String, Object> layer = state.layers.get(layerId);
            if (layer != null) {
                layer.put("order", index++);
            }
        }
    }

    private void applyLayerDelete(CanvasState state, String eventId, Map<String, Object> op) {
        Map<String, Object> layer = state.layers.get(str(op, "layerId"));
        if (layer == null) {
            return;
        }
        state.priorLayerByEventId.put(eventId, new LinkedHashMap<>(layer));
        layer.put("deleted", true);
    }

    private void applyCanvasBg(CanvasState state, String eventId, Map<String, Object> op) {
        String bg = str(op, "canvasBg");
        if (bg == null) {
            return;
        }
        state.priorCanvasBgByEventId.put(eventId, state.canvasBg);
        state.canvasBg = bg;
    }

    private void applyClear(CanvasState state, String eventId) {
        List<String> visibleIds = new ArrayList<>();
        state.objects.forEach((objectId, obj) -> {
            if (!Boolean.TRUE.equals(obj.get("deleted"))) {
                visibleIds.add(objectId);
            }
        });
        state.clearedByEventId.put(eventId, visibleIds);
        visibleIds.forEach(objectId -> state.objects.get(objectId).put("deleted", true));
    }

    private void applyUndo(CanvasState state, String undoEventId, Map<String, Object> op) {
        String targetEventId = str(op, "targetEventId");
        if (targetEventId == null) {
            return;
        }
        Map<String, Object> targetOp = state.opsByEventId.get(targetEventId);
        if (targetOp == null) {
            return; // target predates the latest snapshot or is unknown → graceful no-op
        }
        String targetTypeName = str(targetOp, "type");
        if (targetTypeName == null) {
            return;
        }
        EventType targetType;
        try {
            targetType = EventType.valueOf(targetTypeName);
        } catch (IllegalArgumentException e) {
            return;
        }
        switch (targetType) {
            case BRUSH_STROKE, SHAPE, TEXT, STICKER, OBJECT_DUPLICATE -> {
                String objectId = state.objectIdByEventId.get(targetEventId);
                if (objectId == null) {
                    objectId = str(targetOp, "objectId");
                }
                Map<String, Object> obj = objectId == null ? null : state.objects.get(objectId);
                if (obj == null) {
                    return;
                }
                state.redoInfoByUndoEventId.put(undoEventId, Map.of("kind", "ADD", "objectId", objectId));
                obj.put("deleted", true);
            }
            case OBJECT_TRANSFORM -> {
                String objectId = str(targetOp, "objectId");
                Map<String, Object> obj = objectId == null ? null : state.objects.get(objectId);
                if (obj == null) {
                    return;
                }
                Map<String, Object> redo = new HashMap<>();
                redo.put("kind", "TRANSFORM");
                redo.put("objectId", objectId);
                redo.put("geometry", geometryOf(obj));
                state.redoInfoByUndoEventId.put(undoEventId, redo);
                Map<String, Object> prior = state.priorGeometryByEventId.get(targetEventId);
                if (prior != null) {
                    applyGeometry(obj, prior);
                }
            }
            case OBJECT_DELETE -> {
                String objectId = str(targetOp, "objectId");
                Map<String, Object> obj = objectId == null ? null : state.objects.get(objectId);
                if (obj == null) {
                    return;
                }
                state.redoInfoByUndoEventId.put(undoEventId, Map.of("kind", "DELETE", "objectId", objectId));
                obj.put("deleted", state.priorDeletedByEventId.getOrDefault(targetEventId, Boolean.FALSE));
            }
            case CLEAR -> {
                List<String> ids = state.clearedByEventId.get(targetEventId);
                if (ids == null) {
                    return;
                }
                state.redoInfoByUndoEventId.put(undoEventId,
                        Map.of("kind", "CLEAR", "objectIds", List.copyOf(ids)));
                ids.forEach(objectId -> {
                    Map<String, Object> obj = state.objects.get(objectId);
                    if (obj != null) {
                        obj.put("deleted", false);
                    }
                });
            }
            case LAYER_REORDER -> {
                Map<String, Object> prior = state.priorLayerByEventId.get(targetEventId);
                if (prior == null) {
                    return;
                }
                Map<String, Object> currentOrders = new LinkedHashMap<>();
                state.layers.forEach((layerId, layer) -> currentOrders.put(layerId, layer.get("order")));
                Map<String, Object> redo = new HashMap<>();
                redo.put("kind", "LAYER_ORDERS");
                redo.put("orders", currentOrders);
                state.redoInfoByUndoEventId.put(undoEventId, redo);
                prior.forEach((layerId, order) -> {
                    Map<String, Object> layer = state.layers.get(layerId);
                    if (layer != null && order instanceof Number number) {
                        layer.put("order", number.intValue());
                    }
                });
            }
            case LAYER_UPDATE, LAYER_DELETE -> {
                String layerId = str(targetOp, "layerId");
                Map<String, Object> layer = layerId == null ? null : state.layers.get(layerId);
                Map<String, Object> prior = state.priorLayerByEventId.get(targetEventId);
                if (layer == null || prior == null) {
                    return;
                }
                Map<String, Object> redo = new HashMap<>();
                redo.put("kind", "LAYER");
                redo.put("layerId", layerId);
                redo.put("current", new LinkedHashMap<>(layer));
                state.redoInfoByUndoEventId.put(undoEventId, redo);
                layer.clear();
                layer.putAll(prior);
            }
            case CANVAS_BG -> {
                String prior = state.priorCanvasBgByEventId.get(targetEventId);
                if (prior == null) {
                    return;
                }
                state.redoInfoByUndoEventId.put(undoEventId, Map.of("kind", "BG", "current", state.canvasBg));
                state.canvasBg = prior;
            }
            // UNDO/REDO/CLEAR targets: nothing to invert
            default -> {
            }
        }
    }

    private void applyRedo(CanvasState state, Map<String, Object> op) {
        String undoEventId = str(op, "targetEventId"); // §8: REDO targets the UNDO event's id
        if (undoEventId == null) {
            return;
        }
        Map<String, Object> info = state.redoInfoByUndoEventId.get(undoEventId);
        if (info == null) {
            return;
        }
        String kind = str(info, "kind");
        if (kind == null) {
            return;
        }
        switch (kind) {
            case "ADD" -> {
                Map<String, Object> obj = state.objects.get(str(info, "objectId"));
                if (obj != null) {
                    obj.put("deleted", false);
                }
            }
            case "TRANSFORM" -> {
                Map<String, Object> obj = state.objects.get(str(info, "objectId"));
                if (obj != null && info.get("geometry") instanceof Map<?, ?> geometry) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> geometryMap = (Map<String, Object>) geometry;
                    applyGeometry(obj, geometryMap);
                }
            }
            case "DELETE" -> {
                Map<String, Object> obj = state.objects.get(str(info, "objectId"));
                if (obj != null) {
                    obj.put("deleted", true);
                }
            }
            case "CLEAR" -> {
                List<String> ids = stringList(info, "objectIds");
                if (ids != null) {
                    ids.forEach(objectId -> {
                        Map<String, Object> obj = state.objects.get(objectId);
                        if (obj != null) {
                            obj.put("deleted", true);
                        }
                    });
                }
            }
            case "LAYER" -> {
                Map<String, Object> layer = state.layers.get(str(info, "layerId"));
                if (layer != null && info.get("current") instanceof Map<?, ?> current) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> currentMap = (Map<String, Object>) current;
                    layer.clear();
                    layer.putAll(currentMap);
                }
            }
            case "LAYER_ORDERS" -> {
                if (info.get("orders") instanceof Map<?, ?> orders) {
                    orders.forEach((layerId, order) -> {
                        Map<String, Object> layer = state.layers.get(String.valueOf(layerId));
                        if (layer != null && order instanceof Number number) {
                            layer.put("order", number.intValue());
                        }
                    });
                }
            }
            case "BG" -> {
                Object current = info.get("current");
                if (current != null) {
                    state.canvasBg = current.toString();
                }
            }
            default -> log.debug("Redo skipped unknown kind {}", kind);
        }
    }

    // ------------------------------------------------------------------
    // Snapshot data (§9)
    // ------------------------------------------------------------------

    /**
     * Serializes state to the §9 snapshot shape: {@code {canvasBg, layers (all),
     * objects (live only), lastSequence}}.
     */
    public Map<String, Object> toSnapshotData(CanvasState state, long lastSequence) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("canvasBg", state.canvasBg);
        List<? extends Map<String, Object>> layers = state.layers.values().stream()
                .map(LinkedHashMap::new)
                .toList();
        data.put("layers", layers);
        List<? extends Map<String, Object>> objects = state.objects.values().stream()
                .filter(obj -> !Boolean.TRUE.equals(obj.get("deleted")))
                .map(LinkedHashMap::new)
                .toList();
        data.put("objects", objects);
        data.put("lastSequence", lastSequence);
        return data;
    }

    /** Applies a §9 snapshot map onto a state (starting point for event replay). */
    public void applySnapshotData(CanvasState state, Map<String, Object> snapshotData) {
        if (state == null || snapshotData == null) {
            return;
        }
        String bg = str(snapshotData, "canvasBg");
        if (bg != null && !bg.isBlank()) {
            state.canvasBg = bg;
        }
        if (snapshotData.get("layers") instanceof List<?> layerList) {
            for (Object item : layerList) {
                if (item instanceof Map<?, ?> rawLayer) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> layer = (Map<String, Object>) rawLayer;
                    String layerId = str(layer, "layerId");
                    if (layerId != null) {
                        Map<String, Object> copy = new LinkedHashMap<>(layer);
                        copy.putIfAbsent("deleted", false);
                        state.layers.put(layerId, copy);
                    }
                }
            }
        }
        if (snapshotData.get("objects") instanceof List<?> objectList) {
            for (Object item : objectList) {
                if (item instanceof Map<?, ?> rawObject) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> object = (Map<String, Object>) rawObject;
                    String objectId = str(object, "objectId");
                    if (objectId != null) {
                        Map<String, Object> copy = new LinkedHashMap<>(object);
                        copy.put("deleted", Boolean.TRUE.equals(copy.get("deleted")));
                        state.objects.put(objectId, copy);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String objectTypeName(EventType type, String tool) {
        return switch (type) {
            case BRUSH_STROKE -> "stroke";
            case SHAPE -> "shape";
            case TEXT -> "text";
            case STICKER -> "sticker";
            case OBJECT_DUPLICATE -> inferObjectTypeName(tool);
            default -> "stroke";
        };
    }

    private static String inferObjectTypeName(String tool) {
        if (tool == null) {
            return "stroke";
        }
        try {
            ToolType parsed = ToolType.valueOf(tool);
            if (parsed == ToolType.TEXT) {
                return "text";
            }
            if (parsed == ToolType.STICKER) {
                return "sticker";
            }
            if (SHAPE_TOOLS.contains(parsed)) {
                return "shape";
            }
            return "stroke";
        } catch (IllegalArgumentException e) {
            return "stroke";
        }
    }

    private static Map<String, Object> geometryOf(Map<String, Object> obj) {
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("x", obj.get("x"));
        geometry.put("y", obj.get("y"));
        geometry.put("scale", obj.get("scale"));
        geometry.put("rotation", obj.get("rotation"));
        return geometry;
    }

    private static void applyGeometry(Map<String, Object> obj, Map<String, Object> geometry) {
        Double x = dbl(geometry, "x");
        if (x != null) {
            obj.put("x", x);
        }
        Double y = dbl(geometry, "y");
        if (y != null) {
            obj.put("y", y);
        }
        Double scale = dbl(geometry, "scale");
        if (scale != null) {
            obj.put("scale", scale);
        }
        Double rotation = dbl(geometry, "rotation");
        if (rotation != null) {
            obj.put("rotation", rotation);
        }
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String s ? s : null;
    }

    private static Double dbl(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Integer integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean b ? b : null;
    }

    private static List<String> stringList(Map<String, Object> map, String key) {
        if (map.get(key) instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return null;
    }
}

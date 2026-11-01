package com.drawspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.drawspace.domain.EventType;
import com.drawspace.entity.CanvasSnapshot;
import com.drawspace.entity.DrawingEvent;
import com.drawspace.repository.CanvasSnapshotRepository;
import com.drawspace.repository.DrawingEventRepository;

/**
 * Unit tests for the canonical {@link CanvasStateService} applyOp/replay engine
 * (CONTRACT §8) and the §9 snapshot-data round trip — no Spring context, no DB;
 * repositories are Mockito mocks.
 *
 * <p>Ops are crafted as plain {@code Map<String, Object>} event maps exactly like the
 * ones the services persist in {@code drawing_events.event_data} and feed to
 * {@link CanvasStateService#applyOp}. Everything here tests determinism: the same
 * event sequence must always produce an identical canonical state.</p>
 */
@ExtendWith(MockitoExtension.class)
class CanvasStateServiceTest {

    private static final UUID DRAWING_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ROOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PARTICIPANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Mock
    private CanvasSnapshotRepository canvasSnapshotRepository;
    @Mock
    private DrawingEventRepository drawingEventRepository;

    private CanvasStateService service;

    @BeforeEach
    void setUp() {
        service = new CanvasStateService(canvasSnapshotRepository, drawingEventRepository);
    }

    // ------------------------------------------------------------------
    // fresh state + basic add
    // ------------------------------------------------------------------

    @Test
    void freshStateIsEmptyWhiteCanvas() {
        CanvasStateService.CanvasState state = service.freshState();

        assertThat(state.getCanvasBg()).isEqualTo("#FFFFFF");
        assertThat(state.getLayers()).isEmpty();
        assertThat(state.getObjects()).isEmpty();
        assertThat(state.getLastAppliedSequence()).isZero();
    }

    @Test
    void brushStrokeAddCreatesLiveStrokeObject() {
        CanvasStateService.CanvasState state = service.freshState();
        Map<String, Object> op = strokeOp("e1", "objA");

        service.applyOp(state, op);

        Map<String, Object> object = state.getObjects().get("objA");
        assertThat(object).isNotNull();
        assertThat(object.get("objectId")).isEqualTo("objA");
        assertThat(object.get("layerId")).isEqualTo("layer-1");
        assertThat(object.get("type")).isEqualTo("stroke");
        assertThat(object.get("tool")).isEqualTo("PENCIL");
        assertThat(object.get("color")).isEqualTo("#1E90FF");
        assertThat(object.get("brushSize")).isEqualTo(4);
        assertThat(object.get("opacity")).isEqualTo(0.9);
        assertThat(object.get("deleted")).isEqualTo(false);
        assertThat(object.get("points"))
                .isEqualTo(List.of(Map.of("x", 10.0, "y", 20.0), Map.of("x", 20.0, "y", 30.0)));
    }

    @Test
    void addWithoutObjectIdFallsBackToDeterministicId() {
        CanvasStateService.CanvasState state = service.freshState();
        Map<String, Object> op = strokeOp("e1", null); // no objectId in the op

        service.applyOp(state, op);

        assertThat(state.getObjects()).containsOnlyKeys("op-e1");
    }

    @Test
    void duplicateEventIdReplayIsANoOp() {
        Map<String, Object> op = strokeOp("e1", "objA");

        CanvasStateService.CanvasState appliedOnce = service.freshState();
        service.applyOp(appliedOnce, op);

        CanvasStateService.CanvasState appliedTwice = service.freshState();
        service.applyOp(appliedTwice, op);
        service.applyOp(appliedTwice, op); // replaying the same eventId must not duplicate

        assertThat(appliedTwice.getObjects()).containsOnlyKeys("objA");
        assertThat(appliedTwice.getObjects().get("objA")).isEqualTo(appliedOnce.getObjects().get("objA"));
    }

    @Test
    void applyOpIgnoresNullStateNullOpAndIncompleteOrUnknownOps() {
        CanvasStateService.CanvasState state = service.freshState();

        service.applyOp(null, strokeOp("e1", "objA"));
        service.applyOp(state, null);
        service.applyOp(state, opWithout("e1", "eventId"));   // missing eventId
        service.applyOp(state, opWithout("e1", "type"));      // missing type
        service.applyOp(state, event("e1", "NOT_AN_EVENT_TYPE")); // unknown type

        assertThat(state.getObjects()).isEmpty();
        assertThat(state.getLayers()).isEmpty();
        assertThat(state.getCanvasBg()).isEqualTo("#FFFFFF");
    }

    // ------------------------------------------------------------------
    // OBJECT_TRANSFORM / UNDO / REDO
    // ------------------------------------------------------------------

    @Test
    void objectTransformThenUndoRestoresPriorGeometryAndRedoReAppliesIt() {
        CanvasStateService.CanvasState state = service.freshState();
        Map<String, Object> add = textOp("e1", "objT");
        service.applyOp(state, add);

        Map<String, Object> transform = event("e2", EventType.OBJECT_TRANSFORM);
        transform.put("objectId", "objT");
        transform.put("transform", Map.of("x", 100.5, "y", 200.25, "scale", 2.5, "rotation", 30.0));
        service.applyOp(state, transform);
        Map<String, Object> object = state.getObjects().get("objT");
        assertThat(object.get("x")).isEqualTo(100.5);
        assertThat(object.get("y")).isEqualTo(200.25);
        assertThat(object.get("scale")).isEqualTo(2.5);
        assertThat(object.get("rotation")).isEqualTo(30.0);

        service.applyOp(state, undo("e3", "e2"));
        assertThat(object.get("x")).isEqualTo(10.0);
        assertThat(object.get("y")).isEqualTo(20.0);
        assertThat(object.get("scale")).isEqualTo(1.0);
        assertThat(object.get("rotation")).isEqualTo(0.0);

        service.applyOp(state, redo("e4", "e3")); // REDO targets the UNDO event's id (§8)
        assertThat(object.get("x")).isEqualTo(100.5);
        assertThat(object.get("y")).isEqualTo(200.25);
        assertThat(object.get("scale")).isEqualTo(2.5);
        assertThat(object.get("rotation")).isEqualTo(30.0);
    }

    @Test
    void addThenUndoHidesObjectAndRedoRestoresIt() {
        CanvasStateService.CanvasState state = service.freshState();
        service.applyOp(state, strokeOp("e1", "objA"));

        service.applyOp(state, undo("e2", "e1"));
        assertThat(state.getObjects().get("objA")).containsEntry("deleted", true);

        service.applyOp(state, redo("e3", "e2"));
        assertThat(state.getObjects().get("objA")).containsEntry("deleted", false);
    }

    @Test
    void objectDeleteThenUndoRestoresPriorDeletedFlag() {
        CanvasStateService.CanvasState state = service.freshState();
        service.applyOp(state, strokeOp("e1", "objA"));

        Map<String, Object> delete = event("e2", EventType.OBJECT_DELETE);
        delete.put("objectId", "objA");
        service.applyOp(state, delete);
        assertThat(state.getObjects().get("objA")).containsEntry("deleted", true);

        service.applyOp(state, undo("e3", "e2"));
        assertThat(state.getObjects().get("objA")).containsEntry("deleted", false);
    }

    @Test
    void clearHidesOnlyLiveObjectsAndUndoRestoresExactlyThem() {
        CanvasStateService.CanvasState state = service.freshState();
        service.applyOp(state, strokeOp("e1", "objA"));
        service.applyOp(state, strokeOp("e2", "objB"));

        // objC was deleted BEFORE the clear → it must not be resurrected by the clear-undo
        Map<String, Object> delete = event("e3", EventType.OBJECT_DELETE);
        delete.put("objectId", "objC");
        service.applyOp(state, Map.of("eventId", "e0", "type", EventType.BRUSH_STROKE.name(),
                "objectId", "objC", "layerId", "layer-1", "tool", "PENCIL"));
        service.applyOp(state, delete);

        service.applyOp(state, event("e4", EventType.CLEAR));
        assertThat(state.getObjects().get("objA")).containsEntry("deleted", true);
        assertThat(state.getObjects().get("objB")).containsEntry("deleted", true);
        assertThat(state.getObjects().get("objC")).containsEntry("deleted", true);

        service.applyOp(state, undo("e5", "e4"));
        assertThat(state.getObjects().get("objA")).containsEntry("deleted", false);
        assertThat(state.getObjects().get("objB")).containsEntry("deleted", false);
        assertThat(state.getObjects().get("objC")).containsEntry("deleted", true);
    }

    // ------------------------------------------------------------------
    // layer semantics
    // ------------------------------------------------------------------

    @Test
    void layerCreateAssignsOrderAndDefaults() {
        CanvasStateService.CanvasState state = service.freshState();

        Map<String, Object> first = event("e1", EventType.LAYER_CREATE);
        first.put("layerId", "L1");
        first.put("name", "Background");
        service.applyOp(state, first);

        Map<String, Object> second = event("e2", EventType.LAYER_CREATE);
        second.put("layerId", "L2"); // no name → default "Layer 2"
        service.applyOp(state, second);

        assertThat(state.getLayers().get("L1")).isEqualTo(Map.of(
                "layerId", "L1", "name", "Background", "order", 0,
                "visible", true, "locked", false, "deleted", false));
        assertThat(state.getLayers().get("L2")).isEqualTo(Map.of(
                "layerId", "L2", "name", "Layer 2", "order", 1,
                "visible", true, "locked", false, "deleted", false));
    }

    @Test
    void layerCreateIsIgnoredForDuplicateOrMissingLayerIds() {
        CanvasStateService.CanvasState state = service.freshState();
        Map<String, Object> create = event("e1", EventType.LAYER_CREATE);
        create.put("layerId", "L1");
        create.put("name", "First");
        service.applyOp(state, create);
        service.applyOp(state, create); // duplicate layerId → no-op

        Map<String, Object> unnamed = event("e2", EventType.LAYER_CREATE); // no layerId → no-op
        service.applyOp(state, unnamed);

        assertThat(state.getLayers()).containsOnlyKeys("L1");
        assertThat(state.getLayers().get("L1")).containsEntry("name", "First");
    }

    @Test
    void layerUpdateThenUndoRestoresPriorProps() {
        CanvasStateService.CanvasState state = service.freshState();
        Map<String, Object> create = event("e1", EventType.LAYER_CREATE);
        create.put("layerId", "L1");
        create.put("name", "Layer 1");
        service.applyOp(state, create);

        Map<String, Object> update = event("e2", EventType.LAYER_UPDATE);
        update.put("layerId", "L1");
        update.put("name", "Renamed");
        update.put("visible", false);
        update.put("locked", true);
        service.applyOp(state, update);
        assertThat(state.getLayers().get("L1")).containsEntry("name", "Renamed")
                .containsEntry("visible", false).containsEntry("locked", true);

        service.applyOp(state, undo("e3", "e2"));
        assertThat(state.getLayers().get("L1")).containsEntry("name", "Layer 1")
                .containsEntry("visible", true).containsEntry("locked", false);

        service.applyOp(state, redo("e4", "e3"));
        assertThat(state.getLayers().get("L1")).containsEntry("name", "Renamed")
                .containsEntry("visible", false).containsEntry("locked", true);
    }

    @Test
    void layerReorderThenUndoRestoresPriorOrders() {
        CanvasStateService.CanvasState state = service.freshState();
        Map<String, Object> l1 = event("e1", EventType.LAYER_CREATE);
        l1.put("layerId", "L1");
        Map<String, Object> l2 = event("e2", EventType.LAYER_CREATE);
        l2.put("layerId", "L2");
        service.applyOp(state, l1);
        service.applyOp(state, l2);

        Map<String, Object> reorder = event("e3", EventType.LAYER_REORDER);
        reorder.put("order", List.of("L2", "L1"));
        service.applyOp(state, reorder);
        assertThat(state.getLayers().get("L2")).containsEntry("order", 0);
        assertThat(state.getLayers().get("L1")).containsEntry("order", 1);

        service.applyOp(state, undo("e4", "e3"));
        assertThat(state.getLayers().get("L1")).containsEntry("order", 0);
        assertThat(state.getLayers().get("L2")).containsEntry("order", 1);

        service.applyOp(state, redo("e5", "e4"));
        assertThat(state.getLayers().get("L2")).containsEntry("order", 0);
        assertThat(state.getLayers().get("L1")).containsEntry("order", 1);
    }

    @Test
    void layerDeleteThenUndoRestoresLayer() {
        CanvasStateService.CanvasState state = service.freshState();
        Map<String, Object> create = event("e1", EventType.LAYER_CREATE);
        create.put("layerId", "L1");
        create.put("name", "Gone soon");
        service.applyOp(state, create);

        Map<String, Object> delete = event("e2", EventType.LAYER_DELETE);
        delete.put("layerId", "L1");
        service.applyOp(state, delete);
        assertThat(state.getLayers().get("L1")).containsEntry("deleted", true);

        service.applyOp(state, undo("e3", "e2"));
        assertThat(state.getLayers().get("L1")).containsEntry("deleted", false)
                .containsEntry("name", "Gone soon");
    }

    @Test
    void layerOpsOnUnknownLayersAreGracefulNoOps() {
        CanvasStateService.CanvasState state = service.freshState();

        Map<String, Object> update = event("e1", EventType.LAYER_UPDATE);
        update.put("layerId", "missing");
        update.put("name", "Nope");
        service.applyOp(state, update);

        Map<String, Object> delete = event("e2", EventType.LAYER_DELETE);
        delete.put("layerId", "missing");
        service.applyOp(state, delete);

        Map<String, Object> reorder = event("e3", EventType.LAYER_REORDER);
        reorder.put("order", List.of("missing"));
        service.applyOp(state, reorder);

        assertThat(state.getLayers()).isEmpty();
    }

    // ------------------------------------------------------------------
    // CANVAS_BG
    // ------------------------------------------------------------------

    @Test
    void canvasBgChangeThenUndoRestoresPriorColor() {
        CanvasStateService.CanvasState state = service.freshState();

        Map<String, Object> bg = event("e1", EventType.CANVAS_BG);
        bg.put("canvasBg", "#101010");
        service.applyOp(state, bg);
        assertThat(state.getCanvasBg()).isEqualTo("#101010");

        service.applyOp(state, undo("e2", "e1"));
        assertThat(state.getCanvasBg()).isEqualTo("#FFFFFF");

        service.applyOp(state, redo("e3", "e2"));
        assertThat(state.getCanvasBg()).isEqualTo("#101010");
    }

    @Test
    void canvasBgWithoutColorIsIgnored() {
        CanvasStateService.CanvasState state = service.freshState();
        service.applyOp(state, event("e1", EventType.CANVAS_BG)); // no canvasBg field
        assertThat(state.getCanvasBg()).isEqualTo("#FFFFFF");
    }

    // ------------------------------------------------------------------
    // unknown targets / ids
    // ------------------------------------------------------------------

    @Test
    void undoRedoWithUnknownTargetsAreGracefulNoOps() {
        CanvasStateService.CanvasState state = service.freshState();
        service.applyOp(state, strokeOp("e1", "objA"));

        service.applyOp(state, undo("e2", "missing-target"));   // unknown UNDO target
        service.applyOp(state, redo("e3", "missing-undo"));     // unknown REDO target
        service.applyOp(state, undo("e4", null));               // no target at all

        assertThat(state.getObjects().get("objA")).containsEntry("deleted", false);
    }

    @Test
    void objectOpsOnUnknownObjectsAreGracefulNoOps() {
        CanvasStateService.CanvasState state = service.freshState();

        Map<String, Object> transform = event("e1", EventType.OBJECT_TRANSFORM);
        transform.put("objectId", "ghost");
        transform.put("transform", Map.of("x", 5.0, "y", 5.0));
        service.applyOp(state, transform);

        Map<String, Object> delete = event("e2", EventType.OBJECT_DELETE);
        delete.put("objectId", "ghost");
        service.applyOp(state, delete);

        assertThat(state.getObjects()).isEmpty();
    }

    // ------------------------------------------------------------------
    // replay determinism (§8)
    // ------------------------------------------------------------------

    @Test
    void replayIsDeterministicSameEventsProduceIdenticalState() {
        List<Map<String, Object>> events = List.of(
                withLayerId(event("e1", EventType.LAYER_CREATE), "L1"),
                strokeOp("e2", "objA"),
                strokeOp("e3", "objB"),
                withTransform(event("e4", EventType.OBJECT_TRANSFORM), "objA"),
                withObjectId(event("e5", EventType.OBJECT_DELETE), "objB"),
                event("e6", EventType.CLEAR),
                withCanvasBg(event("e7", EventType.CANVAS_BG), "#2468AC"),
                undo("e8", "e6"),   // un-clear (restores the cleared ids)
                redo("e9", "e8"),   // re-clear (REDO targets the UNDO event's id)
                undo("e10", "e9")); // §8: a REDO op has nothing to invert → graceful no-op

        CanvasStateService.CanvasState first = service.freshState();
        CanvasStateService.CanvasState second = service.freshState();
        events.forEach(op -> {
            service.applyOp(first, op);
            service.applyOp(second, op);
        });

        assertThat(first.getCanvasBg()).isEqualTo(second.getCanvasBg());
        assertThat(first.getLayers()).isEqualTo(second.getLayers());
        assertThat(first.getObjects()).isEqualTo(second.getObjects());
        // objA was cleared again by the REDO; UNDO of a REDO is a no-op (§8 default branch)
        assertThat(first.getObjects().get("objA")).containsEntry("deleted", true);
        assertThat(first.getObjects().get("objB")).containsEntry("deleted", true);
        assertThat(first.getCanvasBg()).isEqualTo("#2468AC");
    }

    // ------------------------------------------------------------------
    // snapshot data round trip (§9)
    // ------------------------------------------------------------------

    @Test
    void snapshotDataRoundTripPreservesCanvasBgLayersAndLiveObjects() {
        CanvasStateService.CanvasState state = service.freshState();
        Map<String, Object> bg = event("e1", EventType.CANVAS_BG);
        bg.put("canvasBg", "#123456");
        service.applyOp(state, bg);
        Map<String, Object> layer = event("e2", EventType.LAYER_CREATE);
        layer.put("layerId", "L1");
        layer.put("name", "Layer 1");
        service.applyOp(state, layer);
        service.applyOp(state, strokeOp("e3", "objA"));
        service.applyOp(state, strokeOp("e4", "objB"));
        Map<String, Object> delete = event("e5", EventType.OBJECT_DELETE);
        delete.put("objectId", "objB");
        service.applyOp(state, delete);

        Map<String, Object> data = service.toSnapshotData(state, 42);
        assertThat(data.get("canvasBg")).isEqualTo("#123456");
        assertThat(data.get("lastSequence")).isEqualTo(42L);
        assertThat((List<?>) data.get("layers")).hasSize(1);
        assertThat((List<?>) data.get("objects")).hasSize(1); // live objects only (§9)

        CanvasStateService.CanvasState restored = service.freshState();
        service.applySnapshotData(restored, data);

        assertThat(restored.getCanvasBg()).isEqualTo("#123456");
        assertThat(restored.getLayers()).containsOnlyKeys("L1");
        assertThat(restored.getLayers().get("L1")).isEqualTo(state.getLayers().get("L1"));
        assertThat(restored.getObjects()).containsOnlyKeys("objA");
        assertThat(restored.getObjects().get("objA")).isEqualTo(state.getObjects().get("objA"));
        assertThat(restored.getObjects().get("objA")).containsEntry("deleted", false);
    }

    @Test
    void applySnapshotDataToleratesNullOrMalformedPayloads() {
        CanvasStateService.CanvasState state = service.freshState();
        service.applySnapshotData(state, null);
        service.applySnapshotData(state, Map.of("layers", "not-a-list", "objects", 42));

        assertThat(state.getCanvasBg()).isEqualTo("#FFFFFF");
        assertThat(state.getLayers()).isEmpty();
        assertThat(state.getObjects()).isEmpty();
    }

    // ------------------------------------------------------------------
    // buildState (snapshot + event replay from repositories) & applyLive
    // ------------------------------------------------------------------

    @Test
    void buildStateAppliesSnapshotThenSubsequentEventsInSequenceOrder() {
        // §9 snapshot shape: {canvasBg, layers (all), objects (live only), lastSequence}
        Map<String, Object> snapshotData = Map.of(
                "canvasBg", "#ABCDEF",
                "layers", List.of(Map.of("layerId", "L1", "name", "Layer 1", "order", 0,
                        "visible", true, "locked", false, "deleted", false)),
                "objects", List.of(Map.of(
                        "objectId", "objA", "layerId", "L1", "type", "stroke", "tool", "PENCIL",
                        "color", "#1E90FF", "brushSize", 4, "opacity", 0.9,
                        "points", List.of(Map.of("x", 10.0, "y", 20.0), Map.of("x", 20.0, "y", 30.0)),
                        "deleted", false)),
                "lastSequence", 5L);
        when(canvasSnapshotRepository.findTopByDrawingIdOrderBySequenceNumberDesc(DRAWING_ID))
                .thenReturn(Optional.of(new CanvasSnapshot(DRAWING_ID, 5L, snapshotData, null, Instant.now())));

        Map<String, Object> bgOp = new LinkedHashMap<>(Map.of(
                "eventId", "e2", "type", EventType.CANVAS_BG.name(), "canvasBg", "#101010"));
        when(drawingEventRepository.findByDrawingIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                DRAWING_ID, 5L, Pageable.unpaged()))
                .thenReturn(List.of(new DrawingEvent(UUID.fromString("00000000-0000-0000-0000-0000000000e2"),
                        DRAWING_ID, ROOM_ID, PARTICIPANT_ID, 6L, EventType.CANVAS_BG.name(),
                        bgOp, Instant.now())));

        CanvasStateService.CanvasState state = service.buildState(DRAWING_ID);

        assertThat(state.getCanvasBg()).isEqualTo("#101010"); // snapshot state then event
        assertThat(state.getObjects()).containsOnlyKeys("objA");
        assertThat(state.getObjects().get("objA")).containsEntry("deleted", false);
        assertThat(state.getLastAppliedSequence()).isEqualTo(6L);
    }

    @Test
    void buildStateWithoutSnapshotStartsFromEmptyState() {
        when(canvasSnapshotRepository.findTopByDrawingIdOrderBySequenceNumberDesc(DRAWING_ID))
                .thenReturn(Optional.empty());
        when(drawingEventRepository.findByDrawingIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                DRAWING_ID, 0L, Pageable.unpaged())).thenReturn(List.of());

        CanvasStateService.CanvasState state = service.buildState(DRAWING_ID);

        assertThat(state.getCanvasBg()).isEqualTo("#FFFFFF");
        assertThat(state.getObjects()).isEmpty();
        assertThat(state.getLastAppliedSequence()).isZero();
    }

    @Test
    void applyLiveAppliesIncrementallyWhileSequenceAdvances() {
        when(canvasSnapshotRepository.findTopByDrawingIdOrderBySequenceNumberDesc(DRAWING_ID))
                .thenReturn(Optional.empty());
        when(drawingEventRepository.findByDrawingIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                DRAWING_ID, 0L, Pageable.unpaged())).thenReturn(List.of());
        service.buildState(DRAWING_ID); // warm the LRU cache (lastAppliedSequence = 0)

        service.applyLive(DRAWING_ID, 1L, strokeOp("e1", "objA"));
        service.applyLive(DRAWING_ID, 2L, strokeOp("e2", "objB"));

        // Neither advancing op rebuilt from the DB — both were applied to the cached state.
        verify(drawingEventRepository, times(1))
                .findByDrawingIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                        DRAWING_ID, 0L, Pageable.unpaged());

        // A same-sequence live op must now take the stale path (sequence <= lastApplied = 2),
        // proving the cached state really advanced to sequence 2.
        service.applyLive(DRAWING_ID, 2L, strokeOp("e3", "objC"));
        verify(canvasSnapshotRepository, times(2))
                .findTopByDrawingIdOrderBySequenceNumberDesc(DRAWING_ID);
    }

    @Test
    void applyLiveWithStaleSequenceTriggersRebuildFromDatabase() {
        when(canvasSnapshotRepository.findTopByDrawingIdOrderBySequenceNumberDesc(DRAWING_ID))
                .thenReturn(Optional.empty());
        when(drawingEventRepository.findByDrawingIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                DRAWING_ID, 0L, Pageable.unpaged())).thenReturn(List.of());
        service.buildState(DRAWING_ID);
        service.applyLive(DRAWING_ID, 3L, strokeOp("e1", "objA"));

        service.applyLive(DRAWING_ID, 3L, strokeOp("e2", "objB")); // not ahead → rebuild

        verify(canvasSnapshotRepository, times(2))
                .findTopByDrawingIdOrderBySequenceNumberDesc(DRAWING_ID); // rebuild hit the DB

        // the rebuild discards the incrementally-applied object (DB is the source of truth)
        CanvasStateService.CanvasState rebuilt = service.buildState(DRAWING_ID);
        assertThat(rebuilt.getObjects()).isEmpty();
        assertThat(rebuilt.getLastAppliedSequence()).isZero();
    }

    // ------------------------------------------------------------------
    // helpers — event maps shaped exactly like persisted event_data (§6/§7)
    // ------------------------------------------------------------------

    private static Map<String, Object> event(String eventId, EventType type) {
        return event(eventId, type.name());
    }

    private static Map<String, Object> event(String eventId, String type) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("eventId", eventId);
        op.put("type", type);
        return op;
    }

    private static Map<String, Object> opWithout(String eventId, String omittedKey) {
        Map<String, Object> op = event(eventId, EventType.BRUSH_STROKE);
        op.remove(omittedKey);
        return op;
    }

    private static Map<String, Object> strokeOp(String eventId, String objectId) {
        Map<String, Object> op = event(eventId, EventType.BRUSH_STROKE);
        if (objectId != null) {
            op.put("objectId", objectId);
        }
        op.put("layerId", "layer-1");
        op.put("tool", "PENCIL");
        op.put("color", "#1E90FF");
        op.put("brushSize", 4);
        op.put("opacity", 0.9);
        op.put("points", List.of(Map.of("x", 10.0, "y", 20.0), Map.of("x", 20.0, "y", 30.0)));
        return op;
    }

    private static Map<String, Object> textOp(String eventId, String objectId) {
        Map<String, Object> op = event(eventId, EventType.TEXT);
        op.put("objectId", objectId);
        op.put("layerId", "layer-1");
        op.put("text", "hello");
        op.put("fontSize", 24);
        op.put("x", 10.0);
        op.put("y", 20.0);
        op.put("scale", 1.0);
        op.put("rotation", 0.0);
        return op;
    }

    private static Map<String, Object> undo(String eventId, String targetEventId) {
        Map<String, Object> op = event(eventId, EventType.UNDO);
        op.put("targetEventId", targetEventId);
        return op;
    }

    private static Map<String, Object> redo(String eventId, String targetUndoEventId) {
        Map<String, Object> op = event(eventId, EventType.REDO);
        op.put("targetEventId", targetUndoEventId);
        return op;
    }

    private static Map<String, Object> withLayerId(Map<String, Object> op, String layerId) {
        op.put("layerId", layerId);
        return op;
    }

    private static Map<String, Object> withObjectId(Map<String, Object> op, String objectId) {
        op.put("objectId", objectId);
        return op;
    }

    private static Map<String, Object> withTransform(Map<String, Object> op, String objectId) {
        op.put("objectId", objectId);
        op.put("transform", Map.of("x", 50.0, "y", 60.0, "scale", 2.0, "rotation", 15.0));
        return op;
    }

    private static Map<String, Object> withCanvasBg(Map<String, Object> op, String canvasBg) {
        op.put("canvasBg", canvasBg);
        return op;
    }
}

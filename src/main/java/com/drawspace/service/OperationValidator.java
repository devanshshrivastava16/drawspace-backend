package com.drawspace.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.drawspace.domain.EventType;
import com.drawspace.domain.LineStyle;
import com.drawspace.domain.TextAlign;
import com.drawspace.domain.ToolType;
import com.drawspace.dto.DrawingOperationMessage;
import com.drawspace.dto.PointDto;
import com.drawspace.dto.TransformDto;
import com.drawspace.exception.InvalidOperationException;

/**
 * Stateless validation of client drawing operations before they enter the save
 * pipeline (CONTRACT §6/§7). Every violation throws {@link InvalidOperationException}
 * with a clear message; the WebSocket layer forwards it to /user/queue/errors (§4).
 *
 * <p>Rules enforced:</p>
 * <ul>
 *   <li>{@code eventId} parses as UUID (idempotency key, §6)</li>
 *   <li>{@code type} ∈ EventType; type/tool compatibility (§5): BRUSH_STROKE →
 *       PENCIL..TEXTURE incl. ERASER, SHAPE → LINE..SPEECH_BUBBLE, TEXT → TEXT,
 *       STICKER → STICKER, all other types accept no tool; SELECT/MOVE/LASSO
 *       are client-side gestures and always rejected</li>
 *   <li>ranges: brushSize 1..100, opacity 0..1, points ≤ 4000 with |x|,|y| ≤ 1e7
 *       (shapes: exactly 2 points), text ≤ 2000 chars (control chars stripped),
 *       font ≤ 64, fontSize 8..200, sides 3..20, scale 0.05..50, rotation -360..360,
 *       sticker ≤ 64, name ≤ 60, width/height &gt; 0 ≤ 100000, colors = #RRGGBB,
 *       transform fields in range</li>
 *   <li>structural: targetEventId required (UUID) for UNDO/REDO; layerId required for
 *       LAYER_* and object ops; objectId required for object ops</li>
 *   <li>payload size: heuristic serialized-size estimate ≤ 512 KB (§6/§7)</li>
 * </ul>
 */
public final class OperationValidator {

    public static final int MAX_POINTS = 4000;
    public static final int MAX_TEXT_LENGTH = 2000;
    public static final int MAX_FONT_LENGTH = 64;
    public static final int MAX_STICKER_LENGTH = 64;
    public static final int MAX_NAME_LENGTH = 60;
    public static final double MAX_COORDINATE = 1.0e7;
    public static final long MAX_PAYLOAD_BYTES = 512 * 1024;

    private static final int MIN_BRUSH_SIZE = 1;
    private static final int MAX_BRUSH_SIZE = 100;
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 200;
    private static final int MIN_SIDES = 3;
    private static final int MAX_SIDES = 20;
    private static final double MIN_SCALE = 0.05;
    private static final double MAX_SCALE = 50.0;
    private static final double MAX_ROTATION = 360.0;
    private static final double MAX_WIDTH_HEIGHT = 100_000.0;

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /** Persistable tools for BRUSH_STROKE (§5): PENCIL..TEXTURE incl. ERASER. */
    private static final Set<ToolType> BRUSH_TOOLS = Set.of(
            ToolType.PENCIL, ToolType.BRUSH, ToolType.PEN, ToolType.MARKER, ToolType.HIGHLIGHTER,
            ToolType.AIRBRUSH, ToolType.ERASER, ToolType.NEON, ToolType.RAINBOW, ToolType.SPRAY,
            ToolType.PIXEL, ToolType.CALLIGRAPHY, ToolType.DOTTED, ToolType.TEXTURE);

    /** Persistable tools for SHAPE (§5): LINE..SPEECH_BUBBLE. */
    private static final Set<ToolType> SHAPE_TOOLS = Set.of(
            ToolType.LINE, ToolType.ARROW, ToolType.RECTANGLE, ToolType.ROUNDED_RECTANGLE,
            ToolType.CIRCLE, ToolType.ELLIPSE, ToolType.TRIANGLE, ToolType.POLYGON,
            ToolType.STAR, ToolType.HEART, ToolType.SPEECH_BUBBLE);

    /** Client-side gestures, never persistable (§5). */
    private static final Set<ToolType> GESTURE_TOOLS = Set.of(ToolType.SELECT, ToolType.MOVE, ToolType.LASSO);

    private OperationValidator() {
    }

    /** Validates the whole op; throws {@link InvalidOperationException} on the first violation. */
    public static void validate(DrawingOperationMessage op) {
        if (op == null) {
            throw new InvalidOperationException("Drawing operation payload is required");
        }
        validateEventId(op);
        EventType type = validateType(op);
        validateTool(op, type);
        validateFields(op, type);
        validateSizeEstimate(op);
    }

    private static void validateEventId(DrawingOperationMessage op) {
        if (op.getEventId() == null || op.getEventId().isBlank()) {
            throw new InvalidOperationException("eventId is required (idempotency key)");
        }
        try {
            UUID.fromString(op.getEventId().trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException("eventId must be a valid UUID");
        }
    }

    private static EventType validateType(DrawingOperationMessage op) {
        if (op.getType() == null || op.getType().isBlank()) {
            throw new InvalidOperationException("type is required");
        }
        try {
            return EventType.valueOf(op.getType().trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException("Unknown event type: " + op.getType());
        }
    }

    private static void validateTool(DrawingOperationMessage op, EventType type) {
        String toolName = op.getTool();
        if (toolName == null || toolName.isBlank()) {
            if (requiresTool(type)) {
                throw new InvalidOperationException("Event type " + type.name() + " requires a tool");
            }
            return;
        }
        ToolType tool;
        try {
            tool = ToolType.valueOf(toolName.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException("Unknown tool: " + toolName);
        }
        if (GESTURE_TOOLS.contains(tool)) {
            throw new InvalidOperationException(
                    "Tool " + tool.name() + " is a client-side gesture and cannot be persisted");
        }
        switch (type) {
            case BRUSH_STROKE -> {
                if (!BRUSH_TOOLS.contains(tool)) {
                    throw new InvalidOperationException("Tool " + tool.name() + " is not valid for BRUSH_STROKE");
                }
            }
            case SHAPE -> {
                if (!SHAPE_TOOLS.contains(tool)) {
                    throw new InvalidOperationException("Tool " + tool.name() + " is not valid for SHAPE");
                }
            }
            case TEXT -> {
                if (tool != ToolType.TEXT) {
                    throw new InvalidOperationException("TEXT ops require the TEXT tool");
                }
            }
            case STICKER -> {
                if (tool != ToolType.STICKER) {
                    throw new InvalidOperationException("STICKER ops require the STICKER tool");
                }
            }
            default -> throw new InvalidOperationException("Event type " + type.name() + " does not accept a tool");
        }
    }

    private static boolean requiresTool(EventType type) {
        return type == EventType.BRUSH_STROKE || type == EventType.SHAPE
                || type == EventType.TEXT || type == EventType.STICKER;
    }

    private static void validateFields(DrawingOperationMessage op, EventType type) {
        if (op.getBrushSize() != null && (op.getBrushSize() < MIN_BRUSH_SIZE || op.getBrushSize() > MAX_BRUSH_SIZE)) {
            throw new InvalidOperationException(
                    "brushSize must be between " + MIN_BRUSH_SIZE + " and " + MAX_BRUSH_SIZE);
        }
        if (op.getOpacity() != null && (!isFinite(op.getOpacity()) || op.getOpacity() < 0 || op.getOpacity() > 1)) {
            throw new InvalidOperationException("opacity must be between 0 and 1");
        }
        if (op.getLineStyle() != null && !op.getLineStyle().isBlank()) {
            try {
                LineStyle.valueOf(op.getLineStyle().trim());
            } catch (IllegalArgumentException e) {
                throw new InvalidOperationException("Unknown lineStyle: " + op.getLineStyle());
            }
        }
        if (op.getAlign() != null && !op.getAlign().isBlank()) {
            try {
                TextAlign.valueOf(op.getAlign().trim());
            } catch (IllegalArgumentException e) {
                throw new InvalidOperationException("Unknown align: " + op.getAlign());
            }
        }
        validatePoints(op, type);
        if (op.getText() != null && stripControlChars(op.getText()).length() > MAX_TEXT_LENGTH) {
            throw new InvalidOperationException("text exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        if (op.getFont() != null && op.getFont().length() > MAX_FONT_LENGTH) {
            throw new InvalidOperationException("font exceeds " + MAX_FONT_LENGTH + " characters");
        }
        if (op.getFontSize() != null && (op.getFontSize() < MIN_FONT_SIZE || op.getFontSize() > MAX_FONT_SIZE)) {
            throw new InvalidOperationException("fontSize must be between " + MIN_FONT_SIZE + " and " + MAX_FONT_SIZE);
        }
        if (op.getSticker() != null && op.getSticker().length() > MAX_STICKER_LENGTH) {
            throw new InvalidOperationException("sticker exceeds " + MAX_STICKER_LENGTH + " characters");
        }
        if (op.getName() != null && op.getName().length() > MAX_NAME_LENGTH) {
            throw new InvalidOperationException("name exceeds " + MAX_NAME_LENGTH + " characters");
        }
        if (op.getSides() != null && (op.getSides() < MIN_SIDES || op.getSides() > MAX_SIDES)) {
            throw new InvalidOperationException("sides must be between " + MIN_SIDES + " and " + MAX_SIDES);
        }
        if (op.getScale() != null
                && (!isFinite(op.getScale()) || op.getScale() < MIN_SCALE || op.getScale() > MAX_SCALE)) {
            throw new InvalidOperationException("scale must be between " + MIN_SCALE + " and " + MAX_SCALE);
        }
        if (op.getRotation() != null
                && (!isFinite(op.getRotation()) || Math.abs(op.getRotation()) > MAX_ROTATION)) {
            throw new InvalidOperationException(
                    "rotation must be between -" + MAX_ROTATION + " and " + MAX_ROTATION);
        }
        validateDimension("width", op.getWidth());
        validateDimension("height", op.getHeight());
        checkHexColor(op.getColor(), "color");
        checkHexColor(op.getFillColor(), "fillColor");
        checkHexColor(op.getBgColor(), "bgColor");
        checkHexColor(op.getCanvasBg(), "canvasBg");
        validateTransform(op.getTransform());
        validateTargetEventId(op, type);
        validateStructuralIds(op, type);
    }

    private static void validatePoints(DrawingOperationMessage op, EventType type) {
        List<PointDto> points = op.getPoints();
        if (points == null) {
            return;
        }
        if (type == EventType.SHAPE && points.size() != 2) {
            throw new InvalidOperationException("SHAPE ops require exactly 2 points, got " + points.size());
        }
        if (points.size() > MAX_POINTS) {
            throw new InvalidOperationException("points exceeds the maximum of " + MAX_POINTS);
        }
        for (PointDto point : points) {
            if (!isFinite(point.x()) || !isFinite(point.y())) {
                throw new InvalidOperationException("points must be finite numbers");
            }
            if (Math.abs(point.x()) > MAX_COORDINATE || Math.abs(point.y()) > MAX_COORDINATE) {
                throw new InvalidOperationException("point coordinates must be within ±" + MAX_COORDINATE);
            }
        }
    }

    private static void validateDimension(String field, Double value) {
        if (value == null) {
            return;
        }
        if (!isFinite(value) || value <= 0 || value > MAX_WIDTH_HEIGHT) {
            throw new InvalidOperationException(field + " must be > 0 and <= " + MAX_WIDTH_HEIGHT);
        }
    }

    private static void validateTransform(TransformDto transform) {
        if (transform == null) {
            return;
        }
        if (!isFinite(transform.x()) || Math.abs(transform.x()) > MAX_COORDINATE
                || !isFinite(transform.y()) || Math.abs(transform.y()) > MAX_COORDINATE) {
            throw new InvalidOperationException("transform x/y must be within ±" + MAX_COORDINATE);
        }
        if (!isFinite(transform.scale()) || transform.scale() < MIN_SCALE || transform.scale() > MAX_SCALE) {
            throw new InvalidOperationException(
                    "transform scale must be between " + MIN_SCALE + " and " + MAX_SCALE);
        }
        if (!isFinite(transform.rotation()) || Math.abs(transform.rotation()) > MAX_ROTATION) {
            throw new InvalidOperationException(
                    "transform rotation must be between -" + MAX_ROTATION + " and " + MAX_ROTATION);
        }
    }

    private static void validateTargetEventId(DrawingOperationMessage op, EventType type) {
        if (type != EventType.UNDO && type != EventType.REDO) {
            return;
        }
        if (op.getTargetEventId() == null || op.getTargetEventId().isBlank()) {
            throw new InvalidOperationException(type.name() + " requires targetEventId");
        }
        try {
            UUID.fromString(op.getTargetEventId().trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException("targetEventId must be a valid UUID");
        }
    }

    private static void validateStructuralIds(DrawingOperationMessage op, EventType type) {
        boolean objectOp = type == EventType.OBJECT_DUPLICATE || type == EventType.OBJECT_TRANSFORM
                || type == EventType.OBJECT_DELETE;
        boolean layerOp = type == EventType.LAYER_CREATE || type == EventType.LAYER_UPDATE
                || type == EventType.LAYER_REORDER || type == EventType.LAYER_DELETE;
        if (objectOp && (op.getObjectId() == null || op.getObjectId().isBlank())) {
            throw new InvalidOperationException(type.name() + " requires objectId");
        }
        if ((objectOp || layerOp) && (op.getLayerId() == null || op.getLayerId().isBlank())) {
            throw new InvalidOperationException(type.name() + " requires layerId");
        }
    }

    private static void checkHexColor(String value, String field) {
        if (value != null && !value.isBlank() && !HEX_COLOR.matcher(value.trim()).matches()) {
            throw new InvalidOperationException(field + " must be a #RRGGBB hex color");
        }
    }

    /**
     * Heuristic serialized-size guard (§6: payload ≤ 512 KB). Estimates the JSON size
     * from field cardinalities instead of serializing: ~24 bytes per point, raw lengths
     * for strings, ~48 bytes per reorder entry.
     */
    private static void validateSizeEstimate(DrawingOperationMessage op) {
        long estimate = 512; // fixed keys + JSON syntax overhead
        if (op.getPoints() != null) {
            estimate += op.getPoints().size() * 24L + 32L;
        }
        if (op.getText() != null) {
            estimate += op.getText().length() + 16L;
        }
        if (op.getSticker() != null) {
            estimate += op.getSticker().length() + 16L;
        }
        if (op.getFont() != null) {
            estimate += op.getFont().length() + 8L;
        }
        if (op.getName() != null) {
            estimate += op.getName().length() + 8L;
        }
        if (op.getOrder() != null) {
            estimate += op.getOrder().size() * 48L + 16L;
        }
        if (op.getTransform() != null) {
            estimate += 96L;
        }
        if (op.getObjectId() != null) {
            estimate += op.getObjectId().length() + 16L;
        }
        if (op.getLayerId() != null) {
            estimate += op.getLayerId().length() + 16L;
        }
        if (op.getTargetEventId() != null) {
            estimate += 48L;
        }
        if (estimate > MAX_PAYLOAD_BYTES) {
            throw new InvalidOperationException("Operation payload exceeds the 512 KB limit");
        }
    }

    /** Removes ASCII control characters (C0 + DEL) from text. */
    private static String stripControlChars(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}

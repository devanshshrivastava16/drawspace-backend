package com.drawspace.domain;

/**
 * Drawing event types (CONTRACT §5 — exact names shared with the frontend).
 */
public enum EventType {
    BRUSH_STROKE,
    SHAPE,
    TEXT,
    STICKER,
    OBJECT_DUPLICATE,
    OBJECT_TRANSFORM,
    OBJECT_DELETE,
    LAYER_CREATE,
    LAYER_UPDATE,
    LAYER_REORDER,
    LAYER_DELETE,
    CANVAS_BG,
    UNDO,
    REDO,
    CLEAR
}

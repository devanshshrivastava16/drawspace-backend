package com.drawspace.domain;

/**
 * Drawing tools (CONTRACT §5 — exact names shared with the frontend).
 *
 * <p>Persistable mapping: {@code BRUSH_STROKE} → PENCIL..TEXTURE/ERASER;
 * {@code SHAPE} → LINE..SPEECH_BUBBLE; {@code TEXT} → TEXT; {@code STICKER} → STICKER.
 * {@code SELECT}/{@code MOVE}/{@code LASSO} are client-side gestures and are rejected as ops.</p>
 */
public enum ToolType {
    PENCIL,
    BRUSH,
    PEN,
    MARKER,
    HIGHLIGHTER,
    AIRBRUSH,
    ERASER,
    NEON,
    RAINBOW,
    SPRAY,
    PIXEL,
    CALLIGRAPHY,
    DOTTED,
    TEXTURE,
    LINE,
    ARROW,
    RECTANGLE,
    ROUNDED_RECTANGLE,
    CIRCLE,
    ELLIPSE,
    TRIANGLE,
    POLYGON,
    STAR,
    HEART,
    SPEECH_BUBBLE,
    TEXT,
    STICKER,
    SELECT,
    MOVE,
    LASSO
}

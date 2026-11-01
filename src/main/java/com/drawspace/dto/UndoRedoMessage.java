package com.drawspace.dto;

/**
 * WS payload for {@code /app/room/{code}/undo} and {@code /app/room/{code}/redo}
 * (CONTRACT §4): {@code {eventId, targetEventId}}.
 *
 * <p>The WebSocket controller normalizes this into a {@link DrawingOperationMessage}
 * with type {@code UNDO}/{@code REDO} and runs the standard save pipeline (§6).
 * For REDO, {@code targetEventId} is the <b>UNDO event's</b> id (§8).</p>
 */
public record UndoRedoMessage(String eventId, String targetEventId) {
}

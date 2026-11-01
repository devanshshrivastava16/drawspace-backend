package com.drawspace.dto;

/**
 * WS payload for /app/room/{code}/clear (CONTRACT §4) and the CLEAR event the owner
 * triggers via DELETE /api/rooms/{roomCode}/canvas (§3 #10) — both persist a CLEAR
 * event through the standard save pipeline (§6).
 */
public record ClearCanvasMessage(String eventId) {
}

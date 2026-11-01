package com.drawspace.dto;

/**
 * WS payload for /app/room/{code}/cursor (CONTRACT §4) — world coordinates.
 * Broadcast enriched on /topic/room/{code}/cursor with participantId/displayName/color;
 * NEVER persisted.
 */
public record CursorMessage(double x, double y) {
}

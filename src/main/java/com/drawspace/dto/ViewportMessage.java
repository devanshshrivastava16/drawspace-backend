package com.drawspace.dto;

/**
 * WS payload for /app/room/{code}/viewport (CONTRACT §4) — minimap indicator ONLY,
 * never persisted (CONTRACT §6: never enters the save pipeline).
 */
public record ViewportMessage(double x, double y, double zoom, double width, double height) {
}

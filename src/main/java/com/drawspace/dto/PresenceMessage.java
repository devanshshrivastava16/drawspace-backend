package com.drawspace.dto;

/**
 * WS payload for /app/room/{code}/presence (CONTRACT §4) — heartbeat sent ~every 25s
 * with {@code status:"ACTIVE"}; updates last_active_at.
 */
public record PresenceMessage(String status) {
}

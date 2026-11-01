package com.drawspace.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * GET /api/rooms/{roomCode} response (CONTRACT §3 #3).
 */
public record RoomDetailsResponse(
        String roomCode,
        String title,
        UUID drawingId,
        UUID ownerParticipantId,
        int maxParticipants,
        long activeParticipantCount,
        boolean isActive,
        Instant createdAt) {
}

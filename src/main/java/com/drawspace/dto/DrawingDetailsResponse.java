package com.drawspace.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * GET /api/drawings/{drawingId} response (CONTRACT §3 #6).
 */
public record DrawingDetailsResponse(
        UUID drawingId,
        String title,
        String roomCode,
        UUID createdByParticipantId,
        String creatorDisplayName,
        Instant createdAt,
        Instant updatedAt,
        long totalOperations,
        long contributorCount) {
}

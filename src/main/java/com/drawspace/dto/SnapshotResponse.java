package com.drawspace.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * GET /api/drawings/{drawingId}/snapshot response (CONTRACT §3 #9);
 * {@code snapshotData} shape per CONTRACT §9.
 */
public record SnapshotResponse(
        UUID drawingId,
        long sequenceNumber,
        Map<String, Object> snapshotData,
        Instant createdAt) {
}

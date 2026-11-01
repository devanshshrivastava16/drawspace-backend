package com.drawspace.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * History event projection (CONTRACT §3 #8): ascending by sequenceNumber;
 * {@code eventData} is the persisted op payload (§7).
 */
public record DrawingEventResponse(
        UUID eventId,
        UUID participantId,
        String displayName,
        long sequenceNumber,
        String eventType,
        Map<String, Object> eventData,
        Instant createdAt) {
}

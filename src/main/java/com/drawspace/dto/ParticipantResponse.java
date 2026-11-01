package com.drawspace.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Participant projection (CONTRACT §3 #4) — also broadcast on
 * /topic/room/{code}/participants after any join/leave/disconnect/cleanup (§4).
 * {@code online} = active per the staleness rule (§10).
 */
public record ParticipantResponse(
        UUID participantId,
        String displayName,
        String color,
        boolean isOwner,
        boolean online,
        Instant joinedAt) {
}

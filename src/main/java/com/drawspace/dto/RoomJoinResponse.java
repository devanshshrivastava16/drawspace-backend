package com.drawspace.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response of POST /api/rooms (§3 #1) and POST /api/rooms/{roomCode}/join (§3 #2 —
 * same shape plus {@code participants}). Identity (participantId/token) is stored by the
 * client in sessionStorage and reused for reconnects (§13).
 */
public record RoomJoinResponse(
        String roomCode,
        String roomTitle,
        UUID drawingId,
        UUID participantId,
        String participantToken,
        String displayName,
        String color,
        int maxParticipants,
        List<ParticipantResponse> participants) {
}

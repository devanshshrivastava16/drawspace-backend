package com.drawspace.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/rooms/{roomCode}/leave request body (CONTRACT §3 #5) — identity + secret
 * verified server-side before the privileged action.
 */
public record LeaveRoomRequest(
        @NotBlank String participantId,
        @NotBlank String participantToken) {
}

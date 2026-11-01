package com.drawspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/rooms/{roomCode}/join request body (CONTRACT §3 #2).
 */
public record JoinRoomRequest(
        @NotBlank @Size(min = 2, max = 24) String displayName) {
}

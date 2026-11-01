package com.drawspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/rooms request body (CONTRACT §3 #1).
 */
public record CreateRoomRequest(
        @NotBlank @Size(min = 2, max = 24) String displayName,
        @Size(max = 60) String roomTitle) {
}

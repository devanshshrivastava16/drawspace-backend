package com.drawspace.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Privileged-action auth payload (CONTRACT §3 #10 — DELETE /api/rooms/{roomCode}/canvas):
 * participantId + participantToken verified server-side.
 */
public record CanvasAuthRequest(
        @NotBlank String participantId,
        @NotBlank String participantToken) {
}

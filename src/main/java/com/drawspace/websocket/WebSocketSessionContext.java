package com.drawspace.websocket;

import java.util.UUID;

/**
 * Immutable authenticated context for one WebSocket session.
 */
public record WebSocketSessionContext(
        UUID roomId,
        String roomCode,
        UUID participantId,
        String displayName,
        String assignedColor
) {
}
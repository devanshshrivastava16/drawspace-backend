package com.drawspace.config;

/**
 * SPI between the WebSocket layer and the application (participant) services.
 *
 * <p>The application layer (B2) must expose an implementation as a Spring {@code @Bean};
 * it verifies that {@code participantToken} is a valid, currently active participant
 * token for {@code roomCode} (room active and unexpired) and returns the authenticated
 * participant's id, or {@code null} when authentication fails (CONNECT is then rejected).</p>
 *
 * <p>Called by {@link WebSocketAuthInterceptor} on every STOMP CONNECT
 * (CONTRACT §1: {@code Authorization: Bearer <participantToken>}, {@code X-Room-Code: <roomCode>}).</p>
 */
@FunctionalInterface
public interface RoomParticipantAuthenticator {

    /**
     * @param roomCode          6-char room code from the CONNECT header
     * @param participantToken  participant token from the {@code Authorization: Bearer} header
     * @return the participantId (UUID string) when valid, otherwise {@code null}
     */
    String authenticate(String roomCode, String participantToken);
}

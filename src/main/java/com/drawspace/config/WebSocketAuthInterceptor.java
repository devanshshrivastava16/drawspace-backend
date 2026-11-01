package com.drawspace.config;

import java.security.Principal;
import java.util.Map;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import com.drawspace.websocket.WebSocketSessionContextRegistrar;

/**
 * STOMP channel interceptor registered on the client inbound channel
 * ({@link WebSocketConfig#configureClientInboundChannel}).
 *
 * <p>CONTRACT §1: STOMP CONNECT carries native headers
 * {@code Authorization: Bearer <participantToken>} and {@code X-Room-Code: <roomCode>}.</p>
 *
 * <ul>
 *   <li>CONNECT: validates via {@link RoomParticipantAuthenticator} (bean provided by the
 *       application layer), sets the STOMP {@link Principal} name = participantId and stores
 *       roomCode + participantId in the WebSocket session attributes. It then registers the
 *       {@link com.drawspace.websocket.WebSocketSessionContext} via
 *       {@link com.drawspace.websocket.WebSocketSessionContextRegistrar} — synchronously in
 *       this preSend, so registration is guaranteed to complete before the CONNECTED frame
 *       reaches the client and the client starts sending. Failure throws
 *       {@link IllegalArgumentException}, which rejects the CONNECT.</li>
 *   <li>SEND: requires an authenticated session and that the destination's room code segment
 *       ({@code /app/room/{code}/...}) matches the CONNECT room code.</li>
 * </ul>
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_ROOM_CODE = "X-Room-Code";
    public static final String BEARER_PREFIX = "Bearer ";

    /** Session-attribute key for the authenticated room code. */
    public static final String SESSION_ROOM_CODE = "roomCode";
    /** Session-attribute key for the authenticated participantId (Principal name). */
    public static final String SESSION_PARTICIPANT_ID = "participantId";

    private static final String APP_ROOM_PREFIX = "/app/room/";

    private final RoomParticipantAuthenticator authenticator;
    private final WebSocketSessionContextRegistrar sessionContextRegistrar;

    public WebSocketAuthInterceptor(
            RoomParticipantAuthenticator authenticator,
            WebSocketSessionContextRegistrar sessionContextRegistrar) {
        this.authenticator = authenticator;
        this.sessionContextRegistrar = sessionContextRegistrar;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        } else if (StompCommand.SEND.equals(accessor.getCommand())) {
            verifySend(accessor);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader(HEADER_AUTHORIZATION);
        String roomCode = accessor.getFirstNativeHeader(HEADER_ROOM_CODE);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new IllegalArgumentException(
                    "CONNECT rejected: missing or malformed 'Authorization: Bearer <participantToken>' header");
        }
        if (roomCode == null || roomCode.isBlank()) {
            throw new IllegalArgumentException("CONNECT rejected: missing 'X-Room-Code' header");
        }
        String participantToken = authorization.substring(BEARER_PREFIX.length()).trim();
        String participantId = authenticator.authenticate(roomCode.trim(), participantToken);
        if (participantId == null || participantId.isBlank()) {
            throw new IllegalArgumentException("CONNECT rejected: invalid room code or participant token");
        }
        accessor.setUser(new StompPrincipal(participantId));
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            sessionAttributes.put(SESSION_ROOM_CODE, roomCode.trim());
            sessionAttributes.put(SESSION_PARTICIPANT_ID, participantId);
        }

        // Register the cached session context inside the CONNECT preSend path so it
        // exists before the CONNECTED frame is sent — no SEND from this session can
        // beat it. Failures throw and reject the CONNECT with a STOMP ERROR frame.
        sessionContextRegistrar.register(
                accessor.getSessionId(),
                roomCode.trim(),
                participantId
        );
    }

    private void verifySend(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null
                || sessionAttributes.get(SESSION_PARTICIPANT_ID) == null
                || sessionAttributes.get(SESSION_ROOM_CODE) == null) {
            throw new IllegalArgumentException(
                    "SEND rejected: no authenticated session (CONNECT with Authorization and X-Room-Code first)");
        }
        String destination = accessor.getDestination();
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("SEND rejected: missing destination");
        }
        String sessionRoomCode = String.valueOf(sessionAttributes.get(SESSION_ROOM_CODE));
        if (!destination.startsWith(APP_ROOM_PREFIX + sessionRoomCode + "/")) {
            throw new IllegalArgumentException(
                    "SEND rejected: destination room does not match the CONNECT room code");
        }
    }

    /**
     * Principal whose {@link #getName()} is the participantId — required so that
     * {@code convertAndSendToUser(participantId, "/queue/ack", ...)} routes acks/errors
     * to the originating participant (CONTRACT §4).
     */
    private record StompPrincipal(String participantId) implements Principal {

        @Override
        public String getName() {
            return participantId;
        }
    }
}

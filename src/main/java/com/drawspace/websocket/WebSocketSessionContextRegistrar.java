package com.drawspace.websocket;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.drawspace.entity.Room;
import com.drawspace.entity.RoomParticipant;
import com.drawspace.repository.RoomParticipantRepository;
import com.drawspace.repository.RoomRepository;

/**
 * Registers a {@link WebSocketSessionContext} during STOMP CONNECT interception.
 *
 * <p>This must run in the inbound CONNECT {@code preSend} path — i.e. before the
 * CONNECTED frame is written to the client — so that no SEND frame from this
 * session can ever be processed before its context exists. A STOMP client is
 * contractually required to wait for CONNECTED before sending, so completing
 * registration here is a deterministic barrier against the
 * "session context unavailable" race that existed when registration happened in
 * {@code SessionConnectedEvent} (published only after CONNECTED reaches the
 * client, plus two PostgreSQL round-trips to Supabase).</p>
 *
 * <p>Any failure throws {@link IllegalArgumentException}, which rejects the
 * CONNECT: the client receives a STOMP ERROR frame and never obtains a valid
 * session context for an unvalidated connection.</p>
 */
@Component
public class WebSocketSessionContextRegistrar {

    private static final Logger log =
            LoggerFactory.getLogger(WebSocketSessionContextRegistrar.class);

    private final RoomRepository roomRepository;
    private final RoomParticipantRepository roomParticipantRepository;
    private final WebSocketSessionContextRegistry contextRegistry;

    public WebSocketSessionContextRegistrar(
            RoomRepository roomRepository,
            RoomParticipantRepository roomParticipantRepository,
            WebSocketSessionContextRegistry contextRegistry) {

        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.contextRegistry = contextRegistry;
    }

    /**
     * Validates the room and participant and registers the session context.
     *
     * @param sessionId     the WebSocket session ID from the CONNECT message
     * @param roomCode      authenticated room code from the CONNECT headers
     * @param participantId authenticated participant ID from the authenticator
     * @return the registered context
     * @throws IllegalArgumentException when validation fails (CONNECT is rejected)
     */
    public WebSocketSessionContext register(
            String sessionId,
            String roomCode,
            String participantId) {

        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("CONNECT rejected: missing WebSocket session id");
        }
        if (roomCode == null || roomCode.isBlank()) {
            throw new IllegalArgumentException("CONNECT rejected: missing room code");
        }
        if (participantId == null || participantId.isBlank()) {
            throw new IllegalArgumentException("CONNECT rejected: missing participant identity");
        }

        UUID parsedParticipantId = parseUuid(participantId);
        if (parsedParticipantId == null) {
            throw new IllegalArgumentException("CONNECT rejected: invalid participant identity");
        }

        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "CONNECT rejected: room not found"));

        RoomParticipant participant = roomParticipantRepository
                .findByRoomIdAndParticipantId(room.getId(), parsedParticipantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "CONNECT rejected: participant is not enrolled in this room"));

        // A participant who has explicitly left must not receive a valid session.
        if (participant.getLeftAt() != null) {
            throw new IllegalArgumentException(
                    "CONNECT rejected: participant has already left this room");
        }

        WebSocketSessionContext context = new WebSocketSessionContext(
                room.getId(),
                roomCode,
                parsedParticipantId,
                participant.getDisplayName(),
                participant.getAssignedColor()
        );

        contextRegistry.register(sessionId, context);

        log.info(
                "WS session context registered: sessionId={}, roomId={}, participantId={}, roomCode={}",
                sessionId,
                room.getId(),
                parsedParticipantId,
                roomCode
        );

        return context;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}

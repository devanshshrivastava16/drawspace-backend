package com.drawspace.websocket;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.drawspace.dto.EventNotice;
import com.drawspace.service.ParticipantService;
import com.drawspace.service.PresenceBroadcaster;

/**
 * Presence lifecycle for WebSocket sessions.
 *
 * <p>Session-context registration happens earlier in the lifecycle — during CONNECT
 * interception via {@link WebSocketSessionContextRegistrar} — so this listener only
 * performs post-registration presence bookkeeping. By the time
 * {@link SessionConnectedEvent} fires (after the CONNECTED frame reaches the client),
 * the context is guaranteed to exist; any message the client sends on this session
 * resolves it without a race.</p>
 */
@Component
public class WebSocketEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(WebSocketEventListener.class);

    private final ParticipantService participantService;
    private final PresenceBroadcaster presenceBroadcaster;
    private final WebSocketSessionContextRegistry contextRegistry;

    public WebSocketEventListener(
            ParticipantService participantService,
            PresenceBroadcaster presenceBroadcaster,
            WebSocketSessionContextRegistry contextRegistry) {

        this.participantService = participantService;
        this.presenceBroadcaster = presenceBroadcaster;
        this.contextRegistry = contextRegistry;
    }

    @EventListener
    public void handleClientConnected(SessionConnectedEvent event) {

        StompHeaderAccessor accessor =
                StompHeaderAccessor.wrap(event.getMessage());

        String sessionId = accessor.getSessionId();

        if (sessionId == null) {
            return;
        }

        // The interceptor registered this context during CONNECT preSend.
        // If it is absent here, the CONNECT was never validated on this session —
        // log loudly, but never fabricate a context or broadcast a join.
        WebSocketSessionContext context = contextRegistry
                .find(sessionId)
                .orElse(null);

        if (context == null) {
            log.warn(
                    "WS session {} connected without a registered session context; "
                            + "no presence broadcast will be made",
                    sessionId
            );
            return;
        }

        participantService.touchPresence(
                context.roomId(),
                context.participantId()
        );

        presenceBroadcaster.broadcastParticipants(context.roomCode());

        presenceBroadcaster.broadcastEvent(
                context.roomCode(),
                new EventNotice(
                        "PARTICIPANT_JOINED",
                        context.participantId().toString(),
                        context.displayName(),
                        context.displayName() + " is online",
                        Instant.now()
                )
        );

        log.debug(
                "WS session {} connected for participant {} in room {}",
                sessionId,
                context.participantId(),
                context.roomCode()
        );
    }

    @EventListener
    public void handleClientDisconnected(SessionDisconnectEvent event) {

        // Remove and retrieve the context atomically.
        WebSocketSessionContext context =
                contextRegistry.remove(event.getSessionId());

        if (context == null) {
            log.debug(
                    "WS session {} disconnected without a registered session context "
                            + "(rejected or already-cleaned session); no presence broadcast",
                    event.getSessionId()
            );
            return;
        }

        // Keep the seat; stale cleanup remains responsible for freeing capacity.
        participantService.touchPresence(
                context.roomId(),
                context.participantId()
        );

        String participantId = context.participantId().toString();
        String roomCode = context.roomCode();
        String displayName = context.displayName();

        presenceBroadcaster.broadcastParticipants(roomCode);

        presenceBroadcaster.broadcastEvent(
                roomCode,
                new EventNotice(
                        "PARTICIPANT_LEFT",
                        participantId,
                        displayName,
                        displayName + " disconnected",
                        Instant.now()
                )
        );

        log.debug(
                "WS session {} disconnected for participant {} in room {}",
                event.getSessionId(),
                participantId,
                roomCode
        );
    }
}
package com.drawspace.websocket;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import com.drawspace.config.WebSocketAuthInterceptor;
import com.drawspace.domain.EventType;
import com.drawspace.dto.ClearCanvasMessage;
import com.drawspace.dto.CursorMessage;
import com.drawspace.dto.DrawingOperationMessage;
import com.drawspace.dto.PresenceMessage;
import com.drawspace.dto.UndoRedoMessage;
import com.drawspace.dto.ViewportMessage;
import com.drawspace.dto.WsErrorMessage;
import com.drawspace.service.DrawingPersistenceService;
import com.drawspace.service.ParticipantService;

@Controller
public class DrawingWebSocketController {

    private static final Logger log =
            LoggerFactory.getLogger(DrawingWebSocketController.class);

    private static final String ERRORS_QUEUE = "/queue/errors";

    private final DrawingPersistenceService drawingPersistenceService;
    private final ParticipantService participantService;
    private final WebSocketSessionContextRegistry contextRegistry;
    private final SimpMessagingTemplate messagingTemplate;

    public DrawingWebSocketController(
            DrawingPersistenceService drawingPersistenceService,
            ParticipantService participantService,
            WebSocketSessionContextRegistry contextRegistry,
            SimpMessagingTemplate messagingTemplate) {

        this.drawingPersistenceService = drawingPersistenceService;
        this.participantService = participantService;
        this.contextRegistry = contextRegistry;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Draw operation.
     * Uses cached participant context; drawing persistence remains unchanged.
     */
    @MessageMapping("room/{roomCode}/draw")
    public void handleDraw(
            @DestinationVariable String roomCode,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor,
            @Payload DrawingOperationMessage operation) {

        String eventId = operation != null ? operation.getEventId() : null;

        WebSocketSessionContext context =
                resolveSessionContext(principal, roomCode, headerAccessor, eventId);

        if (context == null || operation == null) {
            return;
        }

        try {
            drawingPersistenceService.handleOperation(
                    roomCode,
                    context.participantId().toString(),
                    context.displayName(),
                    context.assignedColor(),
                    operation
            );

        } catch (RuntimeException ex) {
            log.debug(
                    "Rejected draw op {} from {} in room {}: {}",
                    eventId,
                    context.participantId(),
                    roomCode,
                    ex.getMessage()
            );

            sendError(
                    principal,
                    eventId,
                    ex.getMessage() != null ? ex.getMessage() : "Operation rejected"
            );
        }
    }

    @MessageMapping("room/{roomCode}/undo")
    public void handleUndo(
            @DestinationVariable String roomCode,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor,
            @Payload UndoRedoMessage message) {

        submitNormalizedOp(
                roomCode,
                principal,
                headerAccessor,
                message != null ? message.eventId() : null,
                EventType.UNDO.name(),
                message != null ? message.targetEventId() : null
        );
    }

    @MessageMapping("room/{roomCode}/redo")
    public void handleRedo(
            @DestinationVariable String roomCode,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor,
            @Payload UndoRedoMessage message) {

        submitNormalizedOp(
                roomCode,
                principal,
                headerAccessor,
                message != null ? message.eventId() : null,
                EventType.REDO.name(),
                message != null ? message.targetEventId() : null
        );
    }

    @MessageMapping("room/{roomCode}/clear")
    public void handleClear(
            @DestinationVariable String roomCode,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor,
            @Payload ClearCanvasMessage message) {

        String eventId =
                message != null
                        && message.eventId() != null
                        && !message.eventId().isBlank()
                        ? message.eventId()
                        : UUID.randomUUID().toString();

        submitNormalizedOp(
                roomCode,
                principal,
                headerAccessor,
                eventId,
                EventType.CLEAR.name(),
                null
        );
    }

    /**
     * Cursor updates are relayed only; no database lookup is needed.
     */
    @MessageMapping("room/{roomCode}/cursor")
    public void handleCursor(
            @DestinationVariable String roomCode,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor,
            @Payload CursorMessage message) {

        WebSocketSessionContext context =
                resolveSessionContext(principal, roomCode, headerAccessor, null);

        if (context == null || message == null) {
            return;
        }

        drawingPersistenceService.handleCursor(
                roomCode,
                context.participantId().toString(),
                context.displayName(),
                context.assignedColor(),
                message
        );
    }

    /**
     * Viewport updates are relayed only; no database lookup is needed.
     */
    @MessageMapping("room/{roomCode}/viewport")
    public void handleViewport(
            @DestinationVariable String roomCode,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor,
            @Payload ViewportMessage message) {

        WebSocketSessionContext context =
                resolveSessionContext(principal, roomCode, headerAccessor, null);

        if (context == null || message == null) {
            return;
        }

        drawingPersistenceService.handleViewport(
                roomCode,
                context.participantId().toString(),
                context.displayName(),
                context.assignedColor(),
                message
        );
    }

    /**
     * Heartbeat uses the cached room and participant IDs.
     * Only the presence update touches PostgreSQL.
     */
    @MessageMapping("room/{roomCode}/presence")
    public void handlePresence(
            @DestinationVariable String roomCode,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor,
            @Payload PresenceMessage message) {

        WebSocketSessionContext context =
                resolveSessionContext(principal, roomCode, headerAccessor, null);

        if (context == null) {
            return;
        }

        participantService.touchPresence(
                context.roomId(),
                context.participantId()
        );
    }

    /**
     * Normalize undo/redo/clear into the existing operation pipeline.
     */
    private void submitNormalizedOp(
            String roomCode,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor,
            String eventId,
            String type,
            String targetEventId) {

        WebSocketSessionContext context =
                resolveSessionContext(principal, roomCode, headerAccessor, eventId);

        if (context == null) {
            return;
        }

        DrawingOperationMessage operation = new DrawingOperationMessage();

        operation.setEventId(eventId);
        operation.setType(type);
        operation.setTargetEventId(targetEventId);
        operation.setClientTimestamp(System.currentTimeMillis());

        try {
            drawingPersistenceService.handleOperation(
                    roomCode,
                    context.participantId().toString(),
                    context.displayName(),
                    context.assignedColor(),
                    operation
            );

        } catch (RuntimeException ex) {
            log.debug(
                    "Rejected {} op from {} in room {}: {}",
                    type,
                    context.participantId(),
                    roomCode,
                    ex.getMessage()
            );

            sendError(
                    principal,
                    eventId,
                    ex.getMessage() != null ? ex.getMessage() : "Operation rejected"
            );
        }
    }

    /**
     * Validates the authenticated Principal and session attributes, then
     * retrieves the already-validated context using the WebSocket session ID.
     */
    private WebSocketSessionContext resolveSessionContext(
            Principal principal,
            String roomCode,
            SimpMessageHeaderAccessor headerAccessor,
            String eventId) {

        if (principal == null
                || principal.getName() == null
                || principal.getName().isBlank()) {

            sendError(principal, eventId, "Unauthenticated WebSocket session");
            return null;
        }

        if (headerAccessor == null || headerAccessor.getSessionId() == null) {
            sendError(principal, eventId, "Invalid WebSocket session");
            return null;
        }

        Map<String, Object> attributes = headerAccessor.getSessionAttributes();

        if (attributes == null) {
            sendError(principal, eventId, "Unauthenticated WebSocket session");
            return null;
        }

        Object roomAttribute =
                attributes.get(WebSocketAuthInterceptor.SESSION_ROOM_CODE);

        Object participantAttribute =
                attributes.get(WebSocketAuthInterceptor.SESSION_PARTICIPANT_ID);

        if (!(roomAttribute instanceof String sessionRoomCode)
                || !(participantAttribute instanceof String sessionParticipantId)) {

            sendError(principal, eventId, "Missing authenticated session attributes");
            return null;
        }

        // Preserve destination-room validation.
        if (!sessionRoomCode.equals(roomCode)) {
            sendError(
                    principal,
                    eventId,
                    "Destination room does not match the authenticated session"
            );
            return null;
        }

        // Ensure the Principal and authenticated session participant agree.
        if (!sessionParticipantId.equals(principal.getName())) {
            sendError(principal, eventId, "Participant identity mismatch");
            return null;
        }

        WebSocketSessionContext context = contextRegistry
                .find(headerAccessor.getSessionId())
                .orElse(null);

        if (context == null) {
            sendError(
                    principal,
                    eventId,
                    "WebSocket session context unavailable. Please reconnect."
            );
            return null;
        }

        // Ensure cached context belongs to this exact authenticated session.
        if (!context.roomCode().equals(roomCode)
                || !context.participantId().toString().equals(principal.getName())) {

            sendError(principal, eventId, "WebSocket session context mismatch");
            return null;
        }

        return context;
    }

    private void sendError(
            Principal principal,
            String eventId,
            String message) {

        String user =
                principal != null && principal.getName() != null
                        ? principal.getName()
                        : "";

        log.debug("WS error → /user/{}/errors: {}", user, message);

        messagingTemplate.convertAndSendToUser(
                user,
                ERRORS_QUEUE,
                new WsErrorMessage(eventId, message)
        );
    }

    @MessageExceptionHandler
    @SendToUser(ERRORS_QUEUE)
    public WsErrorMessage handleException(Exception ex) {

        log.warn(
                "WebSocket message processing failed: {}",
                ex.getMessage()
        );

        return new WsErrorMessage(
                null,
                ex.getMessage() != null
                        ? ex.getMessage()
                        : "Message processing failed"
        );
    }
}
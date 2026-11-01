package com.drawspace.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.drawspace.config.WebSocketAuthInterceptor;
import com.drawspace.dto.ClearCanvasMessage;
import com.drawspace.dto.CursorMessage;
import com.drawspace.dto.DrawingOperationMessage;
import com.drawspace.dto.PresenceMessage;
import com.drawspace.dto.UndoRedoMessage;
import com.drawspace.dto.ViewportMessage;
import com.drawspace.dto.WsErrorMessage;
import com.drawspace.service.DrawingPersistenceService;
import com.drawspace.service.ParticipantService;

@ExtendWith(MockitoExtension.class)
class DrawingWebSocketControllerTest {

    private static final String ROOM_CODE = "ABC234";
    private static final UUID ROOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PARTICIPANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final String PARTICIPANT_ID_STR = PARTICIPANT_ID.toString();
    private static final String SESSION_ID = "test-websocket-session";
    private static final String EVENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String UNDO_EVENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String TARGET_EVENT_ID = "33333333-3333-3333-3333-333333333333";
    private static final String ERRORS_QUEUE = "/queue/errors";

    @Mock private DrawingPersistenceService drawingPersistenceService;
    @Mock private ParticipantService participantService;
    @Mock private WebSocketSessionContextRegistry contextRegistry;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private DrawingWebSocketController controller;

    @BeforeEach
    void setUp() {
        controller = new DrawingWebSocketController(
                drawingPersistenceService, participantService, contextRegistry, messagingTemplate);
    }

    @Test
    void drawWithoutPrincipalSendsUnauthenticatedErrorAndNeverPersists() {
        controller.handleDraw(ROOM_CODE, null, null, opWithEventId(EVENT_ID));

        verify(messagingTemplate).convertAndSendToUser(eq(""), eq(ERRORS_QUEUE),
                argThat(p -> p instanceof WsErrorMessage e
                        && EVENT_ID.equals(e.eventId()) && e.message().contains("Unauthenticated")));
        verifyNoInteractions(drawingPersistenceService, participantService, contextRegistry);
    }

    @Test
    void drawWithMismatchedSessionRoomIsRejectedBeforeContextLookup() {
        controller.handleDraw(ROOM_CODE, principal(PARTICIPANT_ID_STR),
                headers("OTHER01"), opWithEventId(EVENT_ID));

        verify(messagingTemplate).convertAndSendToUser(eq(PARTICIPANT_ID_STR), eq(ERRORS_QUEUE),
                argThat(p -> p instanceof WsErrorMessage e && e.message().contains("does not match")));
        verifyNoInteractions(drawingPersistenceService, participantService, contextRegistry);
    }

    @Test
    void drawDelegatesToPersistenceUsingCachedSessionIdentity() {
        stubSessionContext();
        DrawingOperationMessage operation = opWithEventId(EVENT_ID);

        controller.handleDraw(ROOM_CODE, principal(PARTICIPANT_ID_STR), headers(ROOM_CODE), operation);

        verify(drawingPersistenceService).handleOperation(
                ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", operation);
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void drawWithoutCachedSessionContextIsRejected() {
        when(contextRegistry.find(SESSION_ID)).thenReturn(Optional.empty());

        controller.handleDraw(ROOM_CODE, principal(PARTICIPANT_ID_STR),
                headers(ROOM_CODE), opWithEventId(EVENT_ID));

        verify(messagingTemplate).convertAndSendToUser(
                eq(PARTICIPANT_ID_STR), eq(ERRORS_QUEUE), any(WsErrorMessage.class));
        verifyNoInteractions(drawingPersistenceService);
    }

    @Test
    void drawWithNonUuidPrincipalIsRejected() {
        String invalidId = "not-a-uuid";

        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);

        when(accessor.getSessionId()).thenReturn(SESSION_ID);
        when(accessor.getSessionAttributes()).thenReturn(Map.of(
                WebSocketAuthInterceptor.SESSION_ROOM_CODE, ROOM_CODE,
                WebSocketAuthInterceptor.SESSION_PARTICIPANT_ID, invalidId
        ));

        // No cached context is registered for this session.
        when(contextRegistry.find(SESSION_ID)).thenReturn(Optional.empty());

        controller.handleDraw(
                ROOM_CODE,
                principal(invalidId),
                accessor,
                opWithEventId(EVENT_ID)
        );

        verify(messagingTemplate).convertAndSendToUser(
                eq(invalidId),
                eq(ERRORS_QUEUE),
                argThat(payload -> payload instanceof WsErrorMessage error
                        && error.message().contains(
                        "WebSocket session context unavailable"
                ))
        );

        verifyNoInteractions(drawingPersistenceService);
    }

    @Test
    void drawRejectedByPersistencePipelineForwardsReasonToUserQueue() {
        stubSessionContext();
        when(drawingPersistenceService.handleOperation(
                eq(ROOM_CODE), eq(PARTICIPANT_ID_STR), eq("Alice"), eq("#EF4444"),
                any(DrawingOperationMessage.class)))
                .thenThrow(new RuntimeException("Points exceed the maximum of 4000"));

        controller.handleDraw(ROOM_CODE, principal(PARTICIPANT_ID_STR),
                headers(ROOM_CODE), opWithEventId(EVENT_ID));

        verify(messagingTemplate).convertAndSendToUser(eq(PARTICIPANT_ID_STR), eq(ERRORS_QUEUE),
                argThat(p -> p instanceof WsErrorMessage e
                        && EVENT_ID.equals(e.eventId())
                        && "Points exceed the maximum of 4000".equals(e.message())));
    }

    @Test
    void undoMessageIsNormalizedIntoUndoOperation() {
        stubSessionContext();
        controller.handleUndo(ROOM_CODE, principal(PARTICIPANT_ID_STR), headers(ROOM_CODE),
                new UndoRedoMessage(UNDO_EVENT_ID, TARGET_EVENT_ID));

        DrawingOperationMessage submitted = capturedNormalizedOp();
        assertThat(submitted.getEventId()).isEqualTo(UNDO_EVENT_ID);
        assertThat(submitted.getType()).isEqualTo("UNDO");
        assertThat(submitted.getTargetEventId()).isEqualTo(TARGET_EVENT_ID);
        assertThat(submitted.getClientTimestamp()).isNotNull();
    }

    @Test
    void redoMessageIsNormalizedIntoRedoOperation() {
        stubSessionContext();
        controller.handleRedo(ROOM_CODE, principal(PARTICIPANT_ID_STR), headers(ROOM_CODE),
                new UndoRedoMessage(UNDO_EVENT_ID, TARGET_EVENT_ID));

        DrawingOperationMessage submitted = capturedNormalizedOp();
        assertThat(submitted.getType()).isEqualTo("REDO");
        assertThat(submitted.getTargetEventId()).isEqualTo(TARGET_EVENT_ID);
    }

    @Test
    void clearMessageUsesProvidedEventIdAndGeneratesFallbackWhenBlank() {
        stubSessionContext();
        controller.handleClear(ROOM_CODE, principal(PARTICIPANT_ID_STR), headers(ROOM_CODE),
                new ClearCanvasMessage("clear-event-1"));
        controller.handleClear(ROOM_CODE, principal(PARTICIPANT_ID_STR), headers(ROOM_CODE),
                new ClearCanvasMessage("   "));

        ArgumentCaptor<DrawingOperationMessage> captor =
                ArgumentCaptor.forClass(DrawingOperationMessage.class);
        verify(drawingPersistenceService, times(2)).handleOperation(
                eq(ROOM_CODE), eq(PARTICIPANT_ID_STR), eq("Alice"), eq("#EF4444"), captor.capture());

        DrawingOperationMessage explicit = captor.getAllValues().get(0);
        assertThat(explicit.getEventId()).isEqualTo("clear-event-1");
        assertThat(explicit.getType()).isEqualTo("CLEAR");
        assertThat(explicit.getTargetEventId()).isNull();

        DrawingOperationMessage fallback = captor.getAllValues().get(1);
        assertThat(fallback.getEventId()).isNotBlank();
        assertThat(fallback.getType()).isEqualTo("CLEAR");
    }

    @Test
    void undoWithoutPrincipalSendsErrorAndNeverPersists() {
        controller.handleUndo(ROOM_CODE, null, null,
                new UndoRedoMessage(UNDO_EVENT_ID, TARGET_EVENT_ID));

        verify(messagingTemplate).convertAndSendToUser(eq(""), eq(ERRORS_QUEUE),
                argThat(p -> p instanceof WsErrorMessage e
                        && UNDO_EVENT_ID.equals(e.eventId()) && e.message().contains("Unauthenticated")));
        verifyNoInteractions(drawingPersistenceService, contextRegistry);
    }

    @Test
    void cursorIsRelayedWithCachedParticipantIdentityAndNeverPersistsDrawingOperation() {
        stubSessionContext();
        CursorMessage cursor = new CursorMessage(12.5, -3.0);

        controller.handleCursor(ROOM_CODE, principal(PARTICIPANT_ID_STR), headers(ROOM_CODE), cursor);

        verify(drawingPersistenceService)
                .handleCursor(ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", cursor);
        verify(drawingPersistenceService, never()).handleOperation(
                anyString(), anyString(), anyString(), anyString(), any(DrawingOperationMessage.class));
        verifyNoInteractions(participantService);
    }

    @Test
    void cursorWithoutPrincipalIsDropped() {
        controller.handleCursor(ROOM_CODE, null, null, new CursorMessage(1.0, 2.0));

        verifyNoInteractions(drawingPersistenceService, contextRegistry);
        verify(messagingTemplate).convertAndSendToUser(
                eq(""), eq(ERRORS_QUEUE), any(WsErrorMessage.class));
    }

    @Test
    void viewportIsRelayedAndNeverPersistsDrawingOperation() {
        stubSessionContext();
        ViewportMessage viewport = new ViewportMessage(1.0, 2.0, 1.5, 800.0, 600.0);

        controller.handleViewport(ROOM_CODE, principal(PARTICIPANT_ID_STR), headers(ROOM_CODE), viewport);

        verify(drawingPersistenceService)
                .handleViewport(ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", viewport);
        verify(drawingPersistenceService, never()).handleOperation(
                anyString(), anyString(), anyString(), anyString(), any(DrawingOperationMessage.class));
    }

    @Test
    void presenceHeartbeatTouchesParticipantServiceUsingCachedIds() {
        stubSessionContext();
        controller.handlePresence(ROOM_CODE, principal(PARTICIPANT_ID_STR), headers(ROOM_CODE),
                new PresenceMessage("ACTIVE"));

        verify(participantService).touchPresence(ROOM_ID, PARTICIPANT_ID);
        verifyNoInteractions(drawingPersistenceService);
    }

    @Test
    void presenceWithoutCachedContextDoesNotTouchParticipant() {
        when(contextRegistry.find(SESSION_ID)).thenReturn(Optional.empty());
        controller.handlePresence(ROOM_CODE, principal(PARTICIPANT_ID_STR), headers(ROOM_CODE),
                new PresenceMessage("ACTIVE"));

        verify(participantService, never()).touchPresence(any(UUID.class), any(UUID.class));
    }

    @Test
    void handlerExceptionReturnsUserFacingErrorWithNullEventId() {
        WsErrorMessage error = controller.handleException(new IllegalStateException("boom"));
        assertThat(error.eventId()).isNull();
        assertThat(error.message()).isEqualTo("boom");

        WsErrorMessage fallback = controller.handleException(new IllegalStateException());
        assertThat(fallback.message()).isEqualTo("Message processing failed");
    }

    private static Principal principal(String name) {
        return () -> name;
    }

    private SimpMessageHeaderAccessor headers(String roomCode) {
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionId()).thenReturn(SESSION_ID);
        when(accessor.getSessionAttributes()).thenReturn(Map.of(
                WebSocketAuthInterceptor.SESSION_ROOM_CODE, roomCode,
                WebSocketAuthInterceptor.SESSION_PARTICIPANT_ID, PARTICIPANT_ID_STR));
        return accessor;
    }

    private void stubSessionContext() {
        WebSocketSessionContext context = new WebSocketSessionContext(
                ROOM_ID, ROOM_CODE, PARTICIPANT_ID, "Alice", "#EF4444");
        when(contextRegistry.find(SESSION_ID)).thenReturn(Optional.of(context));
    }

    private static DrawingOperationMessage opWithEventId(String eventId) {
        DrawingOperationMessage operation = new DrawingOperationMessage();
        operation.setEventId(eventId);
        operation.setType("BRUSH_STROKE");
        return operation;
    }

    private DrawingOperationMessage capturedNormalizedOp() {
        ArgumentCaptor<DrawingOperationMessage> captor =
                ArgumentCaptor.forClass(DrawingOperationMessage.class);
        verify(drawingPersistenceService).handleOperation(
                eq(ROOM_CODE), eq(PARTICIPANT_ID_STR), eq("Alice"), eq("#EF4444"), captor.capture());
        return captor.getValue();
    }
}

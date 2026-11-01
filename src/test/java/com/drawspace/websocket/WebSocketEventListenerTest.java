package com.drawspace.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.drawspace.dto.EventNotice;
import com.drawspace.service.ParticipantService;
import com.drawspace.service.PresenceBroadcaster;

@ExtendWith(MockitoExtension.class)
class WebSocketEventListenerTest {

    private static final String ROOM_CODE = "ABC234";
    private static final UUID ROOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PARTICIPANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Mock private ParticipantService participantService;
    @Mock private PresenceBroadcaster presenceBroadcaster;

    // A real registry so multi-session and disconnect-isolation behavior is tested
    // against the actual ConcurrentHashMap implementation.
    private final WebSocketSessionContextRegistry contextRegistry =
            new WebSocketSessionContextRegistry();

    private WebSocketEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new WebSocketEventListener(participantService, presenceBroadcaster, contextRegistry);
    }

    private SessionConnectedEvent connectedEvent(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECTED);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        Message<byte[]> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectedEvent(this, message, () -> PARTICIPANT_ID.toString());
    }

    private SessionDisconnectEvent disconnectEvent(String sessionId) {
        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getSessionId()).thenReturn(sessionId);
        return event;
    }

    private void registerSession(String sessionId, UUID participantId) {
        contextRegistry.register(sessionId, new WebSocketSessionContext(
                ROOM_ID, ROOM_CODE, participantId, "Alice", "#EF4444"));
    }

    @Test
    void connectedEventWithRegisteredContextTouchesPresenceAndBroadcastsJoin() {
        registerSession("ws-session-1", PARTICIPANT_ID);

        listener.handleClientConnected(connectedEvent("ws-session-1"));

        verify(participantService).touchPresence(ROOM_ID, PARTICIPANT_ID);
        verify(presenceBroadcaster).broadcastParticipants(ROOM_CODE);
        ArgumentCaptor<EventNotice> notice = ArgumentCaptor.forClass(EventNotice.class);
        verify(presenceBroadcaster).broadcastEvent(org.mockito.ArgumentMatchers.eq(ROOM_CODE), notice.capture());
        assertThat(notice.getValue().type()).isEqualTo("PARTICIPANT_JOINED");
        assertThat(notice.getValue().participantId()).isEqualTo(PARTICIPANT_ID.toString());
    }

    @Test
    void connectedEventWithoutRegisteredContextNeverBroadcastsOrTouchesPresence() {
        // Simulates a session that never went through successful CONNECT registration.
        listener.handleClientConnected(connectedEvent("unregistered-session"));

        verifyNoInteractions(participantService, presenceBroadcaster);
    }

    @Test
    void disconnectRemovesOnlyTheMatchingSession() {
        UUID otherParticipant = UUID.fromString("30000000-0000-0000-0000-000000000009");
        registerSession("ws-session-1", PARTICIPANT_ID);
        registerSession("ws-session-2", otherParticipant);

        listener.handleClientDisconnected(disconnectEvent("ws-session-1"));

        assertThat(contextRegistry.find("ws-session-1")).isEmpty();
        assertThat(contextRegistry.find("ws-session-2")).isPresent();

        verify(participantService).touchPresence(ROOM_ID, PARTICIPANT_ID);
        verify(presenceBroadcaster).broadcastParticipants(ROOM_CODE);
        ArgumentCaptor<EventNotice> notice = ArgumentCaptor.forClass(EventNotice.class);
        verify(presenceBroadcaster).broadcastEvent(org.mockito.ArgumentMatchers.eq(ROOM_CODE), notice.capture());
        assertThat(notice.getValue().type()).isEqualTo("PARTICIPANT_LEFT");
    }

    @Test
    void disconnectOfUnknownSessionDoesNothing() {
        listener.handleClientDisconnected(disconnectEvent("ghost-session"));

        verifyNoInteractions(participantService, presenceBroadcaster);
        assertThat(contextRegistry.size()).isZero();
    }

    @Test
    void reconnectAfterDisconnectCreatesAIndependentContext() {
        registerSession("ws-session-1", PARTICIPANT_ID);
        listener.handleClientDisconnected(disconnectEvent("ws-session-1"));

        // The client reconnects; the registrar (CONNECT path) registers a new session id.
        registerSession("ws-session-2", PARTICIPANT_ID);
        listener.handleClientConnected(connectedEvent("ws-session-2"));

        assertThat(contextRegistry.find("ws-session-1")).isEmpty();
        assertThat(contextRegistry.find("ws-session-2")).isPresent();
        // once on disconnect of session-1, once on connect of session-2
        verify(participantService, org.mockito.Mockito.times(2)).touchPresence(ROOM_ID, PARTICIPANT_ID);
    }

    @Test
    void twoTabsOfSameParticipantAreIndependentSessions() {
        registerSession("tab-1", PARTICIPANT_ID);
        registerSession("tab-2", PARTICIPANT_ID);

        listener.handleClientConnected(connectedEvent("tab-1"));
        listener.handleClientDisconnected(disconnectEvent("tab-1"));

        assertThat(contextRegistry.find("tab-1")).isEmpty();
        assertThat(contextRegistry.find("tab-2")).isPresent();

        listener.handleClientConnected(connectedEvent("tab-2"));
        // tab-1 connect, tab-1 disconnect, tab-2 connect
        verify(participantService, org.mockito.Mockito.times(3)).touchPresence(ROOM_ID, PARTICIPANT_ID);
        verify(presenceBroadcaster, org.mockito.Mockito.never())
                .broadcastEvent(any(String.class), org.mockito.ArgumentMatchers.argThat(
                        n -> n != null && "PARTICIPANT_LEFT".equals(n.type()) && "tab-2".equals(n.participantId())));
    }
}

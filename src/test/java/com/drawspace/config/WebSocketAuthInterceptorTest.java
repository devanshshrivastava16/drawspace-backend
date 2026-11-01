package com.drawspace.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

import com.drawspace.websocket.WebSocketSessionContext;
import com.drawspace.websocket.WebSocketSessionContextRegistrar;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    private static final String ROOM_CODE = "ABC234";
    private static final String PARTICIPANT_ID = "30000000-0000-0000-0000-000000000003";
    private static final String SESSION_ID = "ws-session-1";
    private static final String TOKEN = "participant-token";

    @Mock private RoomParticipantAuthenticator authenticator;
    @Mock private WebSocketSessionContextRegistrar registrar;
    @Mock private MessageChannel channel;

    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthInterceptor(authenticator, registrar);
    }

    private Message<byte[]> connectMessage(Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(SESSION_ID);
        if (sessionAttributes != null) {
            accessor.setSessionAttributes(sessionAttributes);
        }
        accessor.setNativeHeader(WebSocketAuthInterceptor.HEADER_AUTHORIZATION, "Bearer " + TOKEN);
        accessor.setNativeHeader(WebSocketAuthInterceptor.HEADER_ROOM_CODE, ROOM_CODE);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Map<String, Object> attributes() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAuthInterceptor.SESSION_ROOM_CODE, ROOM_CODE);
        attributes.put(WebSocketAuthInterceptor.SESSION_PARTICIPANT_ID, PARTICIPANT_ID);
        return attributes;
    }

    @Test
    void connectAuthenticatesSetsPrincipalAndRegistersContextDuringPreSend() {
        when(authenticator.authenticate(ROOM_CODE, TOKEN)).thenReturn(PARTICIPANT_ID);
        WebSocketSessionContext context = mock(WebSocketSessionContext.class);
        when(registrar.register(SESSION_ID, ROOM_CODE, PARTICIPANT_ID)).thenReturn(context);

        Map<String, Object> sessionAttributes = attributes();
        Message<?> result = interceptor.preSend(connectMessage(sessionAttributes), channel);

        assertThat(result).isNotNull();
        StompHeaderAccessor wrapped =
                MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(wrapped).isNotNull();
        assertThat(wrapped.getUser()).isNotNull();
        assertThat(wrapped.getUser().getName()).isEqualTo(PARTICIPANT_ID);
        assertThat(sessionAttributes.get(WebSocketAuthInterceptor.SESSION_ROOM_CODE)).isEqualTo(ROOM_CODE);
        assertThat(sessionAttributes.get(WebSocketAuthInterceptor.SESSION_PARTICIPANT_ID)).isEqualTo(PARTICIPANT_ID);

        // Registration happens inside the CONNECT preSend, keyed by the same
        // session id the controller later resolves messages with.
        verify(registrar).register(SESSION_ID, ROOM_CODE, PARTICIPANT_ID);
    }

    @Test
    void failedAuthenticationRejectsConnectBeforeRegistration() {
        when(authenticator.authenticate(ROOM_CODE, TOKEN)).thenReturn(null);

        assertThatThrownBy(() -> interceptor.preSend(connectMessage(attributes()), channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONNECT rejected");

        verifyNoInteractions(registrar);
    }

    @Test
    void failedContextRegistrationRejectsConnect() {
        when(authenticator.authenticate(ROOM_CODE, TOKEN)).thenReturn(PARTICIPANT_ID);
        when(registrar.register(eq(SESSION_ID), eq(ROOM_CODE), eq(PARTICIPANT_ID)))
                .thenThrow(new IllegalArgumentException("CONNECT rejected: participant has already left this room"));

        assertThatThrownBy(() -> interceptor.preSend(connectMessage(attributes()), channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already left");
    }

    @Test
    void connectWithoutAuthorizationHeaderIsRejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(SESSION_ID);
        accessor.setNativeHeader(WebSocketAuthInterceptor.HEADER_ROOM_CODE, ROOM_CODE);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Authorization");

        verifyNoInteractions(authenticator, registrar);
    }

    @Test
    void sendWithoutAuthenticatedSessionAttributesIsRejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(SESSION_ID);
        accessor.setDestination("/app/room/" + ROOM_CODE + "/draw");
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no authenticated session");

        verifyNoInteractions(registrar);
    }

    @Test
    void sendToForeignRoomDestinationIsRejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(SESSION_ID);
        accessor.setSessionAttributes(attributes());
        accessor.setDestination("/app/room/OTHER01/draw");
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(registrar, never()).register(anyString(), anyString(), anyString());
    }
}

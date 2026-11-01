package com.drawspace.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.drawspace.entity.Room;
import com.drawspace.entity.RoomParticipant;
import com.drawspace.repository.RoomParticipantRepository;
import com.drawspace.repository.RoomRepository;

@ExtendWith(MockitoExtension.class)
class WebSocketSessionContextRegistrarTest {

    private static final String ROOM_CODE = "ABC234";
    private static final UUID ROOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PARTICIPANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final String SESSION_ID = "ws-session-1";

    @Mock private RoomRepository roomRepository;
    @Mock private RoomParticipantRepository roomParticipantRepository;
    @Mock private WebSocketSessionContextRegistry contextRegistry;
    @Mock private Room room;
    @Mock private RoomParticipant participant;

    private WebSocketSessionContextRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new WebSocketSessionContextRegistrar(
                roomRepository, roomParticipantRepository, contextRegistry);
    }

    @Test
    void successfulRegistrationStoresContextUnderWebSocketSessionId() {
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        when(room.getId()).thenReturn(ROOM_ID);
        when(roomParticipantRepository.findByRoomIdAndParticipantId(ROOM_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(participant));
        when(participant.getLeftAt()).thenReturn(null);
        when(participant.getDisplayName()).thenReturn("Alice");
        when(participant.getAssignedColor()).thenReturn("#EF4444");

        WebSocketSessionContext context =
                registrar.register(SESSION_ID, ROOM_CODE, PARTICIPANT_ID.toString());

        assertThat(context.roomId()).isEqualTo(ROOM_ID);
        assertThat(context.roomCode()).isEqualTo(ROOM_CODE);
        assertThat(context.participantId()).isEqualTo(PARTICIPANT_ID);
        assertThat(context.displayName()).isEqualTo("Alice");
        assertThat(context.assignedColor()).isEqualTo("#EF4444");

        verify(contextRegistry).register(SESSION_ID, context);
    }

    @Test
    void nonexistentRoomRejectsRegistration() {
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registrar.register(SESSION_ID, ROOM_CODE, PARTICIPANT_ID.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("room not found");

        verifyNoInteractions(contextRegistry);
        verifyNoInteractions(roomParticipantRepository);
    }

    @Test
    void participantNotEnrolledRejectsRegistration() {
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        when(room.getId()).thenReturn(ROOM_ID);
        when(roomParticipantRepository.findByRoomIdAndParticipantId(ROOM_ID, PARTICIPANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> registrar.register(SESSION_ID, ROOM_CODE, PARTICIPANT_ID.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not enrolled");

        verify(contextRegistry, never()).register(anyString(), any());
    }

    @Test
    void participantWithLeftAtSetRejectsRegistration() {
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        when(room.getId()).thenReturn(ROOM_ID);
        when(roomParticipantRepository.findByRoomIdAndParticipantId(ROOM_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(participant));
        when(participant.getLeftAt()).thenReturn(java.time.Instant.now());

        assertThatThrownBy(() -> registrar.register(SESSION_ID, ROOM_CODE, PARTICIPANT_ID.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already left");

        verify(contextRegistry, never()).register(anyString(), any());
    }

    @Test
    void invalidParticipantUuidRejectsRegistration() {
        assertThatThrownBy(() -> registrar.register(SESSION_ID, ROOM_CODE, "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid participant identity");

        verifyNoInteractions(roomRepository, roomParticipantRepository, contextRegistry);
    }

    @Test
    void missingSessionIdRoomCodeOrParticipantIdRejectsRegistration() {
        assertThatThrownBy(() -> registrar.register(null, ROOM_CODE, PARTICIPANT_ID.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session id");
        assertThatThrownBy(() -> registrar.register("  ", ROOM_CODE, PARTICIPANT_ID.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registrar.register(SESSION_ID, null, PARTICIPANT_ID.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("room code");
        assertThatThrownBy(() -> registrar.register(SESSION_ID, ROOM_CODE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("participant identity");

        verifyNoInteractions(roomRepository, roomParticipantRepository, contextRegistry);
    }

    @Test
    void concurrentSessionsForSameParticipantEachGetTheirOwnContext() {
        RoomParticipant otherParticipant = mock(RoomParticipant.class);
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        when(room.getId()).thenReturn(ROOM_ID);
        when(roomParticipantRepository.findByRoomIdAndParticipantId(ROOM_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(participant))
                .thenReturn(Optional.of(otherParticipant));
        when(participant.getLeftAt()).thenReturn(null);
        when(participant.getDisplayName()).thenReturn("Alice");
        when(participant.getAssignedColor()).thenReturn("#EF4444");
        when(otherParticipant.getLeftAt()).thenReturn(null);
        when(otherParticipant.getDisplayName()).thenReturn("Alice");
        when(otherParticipant.getAssignedColor()).thenReturn("#EF4444");

        WebSocketSessionContext first = registrar.register("ws-session-1", ROOM_CODE, PARTICIPANT_ID.toString());
        WebSocketSessionContext second = registrar.register("ws-session-2", ROOM_CODE, PARTICIPANT_ID.toString());

        assertThat(first).isNotSameAs(second);
        verify(contextRegistry).register("ws-session-1", first);
        verify(contextRegistry).register("ws-session-2", second);
    }
}

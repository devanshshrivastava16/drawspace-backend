package com.drawspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.drawspace.config.DrawSpaceProperties;
import com.drawspace.dto.CreateRoomRequest;
import com.drawspace.dto.EventNotice;
import com.drawspace.dto.JoinRoomRequest;
import com.drawspace.dto.LeaveRoomRequest;
import com.drawspace.dto.RoomJoinResponse;
import com.drawspace.entity.Drawing;
import com.drawspace.entity.Room;
import com.drawspace.entity.RoomParticipant;
import com.drawspace.exception.InvalidTokenException;
import com.drawspace.exception.RoomFullException;
import com.drawspace.exception.RoomInactiveException;
import com.drawspace.exception.RoomNotFoundException;
import com.drawspace.repository.DrawingRepository;
import com.drawspace.repository.RoomParticipantRepository;
import com.drawspace.repository.RoomRepository;
import com.drawspace.util.ColorPalette;

/**
 * Unit tests for the room lifecycle (CONTRACT §3 #1–#5, §10) — no Spring context, no DB.
 * Uses a REAL {@link ParticipantService} over mocked repositories so color-assignment
 * integration (§2) is covered end-to-end through RoomService.
 */
@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    private static final UUID ROOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID JOINER_A_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID JOINER_B_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID OWNER_TOKEN = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final String ROOM_CODE = "ABC234";

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private DrawingRepository drawingRepository;
    @Mock
    private RoomParticipantRepository roomParticipantRepository;
    @Mock
    private PresenceBroadcaster presenceBroadcaster;
    @Mock
    private DrawingPersistenceService drawingPersistenceService;

    private final DrawSpaceProperties properties = new DrawSpaceProperties(
            24, 500, 10, 5, new DrawSpaceProperties.Cors("http://localhost:5173"));

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        ParticipantService participantService =
                new ParticipantService(roomRepository, roomParticipantRepository, properties);
        roomService = new RoomService(roomRepository, drawingRepository, roomParticipantRepository,
                participantService, presenceBroadcaster, properties, drawingPersistenceService);
    }

    // ------------------------------------------------------------------
    // createRoom (§3 #1)
    // ------------------------------------------------------------------

    @Test
    void createRoomRetriesOnRoomCodeCollision() {
        when(roomRepository.existsByRoomCode(anyString())).thenReturn(true, true, false);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(drawingRepository.save(any(Drawing.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roomParticipantRepository.save(any(RoomParticipant.class))).thenAnswer(inv -> inv.getArgument(0));

        RoomJoinResponse response = roomService.createRoom(new CreateRoomRequest("Alice", "My room"));

        verify(roomRepository, times(3)).existsByRoomCode(anyString());
        assertThat(response.roomCode()).isNotBlank().hasSize(6);
        assertThat(response.roomTitle()).isEqualTo("My room");
        assertThat(response.maxParticipants()).isEqualTo(5);
    }

    @Test
    void createRoomOwnerGetsFirstPaletteColorAndIdentity() {
        when(roomRepository.existsByRoomCode(anyString())).thenReturn(false);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> {
            Room room = inv.getArgument(0);
            ReflectionTestUtils.setField(room, "id", ROOM_ID);
            return room;
        });
        when(drawingRepository.save(any(Drawing.class))).thenAnswer(inv -> {
            Drawing drawing = inv.getArgument(0);
            ReflectionTestUtils.setField(drawing, "id", UUID.randomUUID());
            return drawing;
        });
        when(roomParticipantRepository.save(any(RoomParticipant.class))).thenAnswer(inv -> inv.getArgument(0));

        RoomJoinResponse response = roomService.createRoom(new CreateRoomRequest("Alice", null));

        assertThat(response.color()).isEqualTo(ColorPalette.COLORS.get(0));
        assertThat(response.displayName()).isEqualTo("Alice");
        assertThat(response.participantId()).isNotNull();
        assertThat(response.participantToken()).isNotBlank();
        UUID.fromString(response.participantToken()); // parses as UUID
        assertThat(response.participants()).hasSize(1);

        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository).save(roomCaptor.capture());
        Instant now = Instant.now();
        assertThat(roomCaptor.getValue().getExpiresAt())
                .isAfter(now.plus(Duration.ofHours(24)).minusSeconds(30))
                .isBefore(now.plus(Duration.ofHours(24)).plusSeconds(30));
        assertThat(roomCaptor.getValue().isActive()).isTrue();
    }

    // ------------------------------------------------------------------
    // joinRoom (§3 #2, §10)
    // ------------------------------------------------------------------

    @Test
    void joinUnknownRoomCodeThrowsNotFound() {
        when(roomRepository.findByRoomCode("ZZZ999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.joinRoom("ZZZ999", new JoinRoomRequest("Bob")))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessageContaining("ZZZ999");
    }

    @Test
    void joinInactiveRoomThrowsGone() {
        Room room = new Room(ROOM_CODE, "T", OWNER_ID, 5, Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS), false);
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> roomService.joinRoom(ROOM_CODE, new JoinRoomRequest("Bob")))
                .isInstanceOf(RoomInactiveException.class);
    }

    @Test
    void joinExpiredRoomThrowsGone() {
        Room room = new Room(ROOM_CODE, "T", OWNER_ID, 5, Instant.now().minus(25, ChronoUnit.HOURS),
                Instant.now().minus(1, ChronoUnit.HOURS), true);
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> roomService.joinRoom(ROOM_CODE, new JoinRoomRequest("Bob")))
                .isInstanceOf(RoomInactiveException.class);
    }

    @Test
    void joinFullRoomThrowsConflictAndCountsOnlyRecentActivity() {
        Room room = activeRoom();
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        when(roomRepository.findByIdForUpdate(ROOM_ID)).thenReturn(Optional.of(room));
        when(roomParticipantRepository.countActiveByRoomId(eq(ROOM_ID), any(Instant.class))).thenReturn(5L);

        assertThatThrownBy(() -> roomService.joinRoom(ROOM_CODE, new JoinRoomRequest("Bob")))
                .isInstanceOf(RoomFullException.class);

        // §10: capacity counts only participants active within the stale threshold
        ArgumentCaptor<Instant> thresholdCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(roomParticipantRepository).countActiveByRoomId(eq(ROOM_ID), thresholdCaptor.capture());
        Instant expectedThreshold = Instant.now().minus(properties.staleThresholdMinutes(), ChronoUnit.MINUTES);
        assertThat(Math.abs(Duration.between(thresholdCaptor.getValue(), expectedThreshold).toSeconds()))
                .isLessThan(5);
    }

    @Test
    void joinAssignsFirstUnusedPaletteColor() {
        Room room = activeRoom();
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        when(roomRepository.findByIdForUpdate(ROOM_ID)).thenReturn(Optional.of(room));
        when(roomParticipantRepository.countActiveByRoomId(eq(ROOM_ID), any(Instant.class))).thenReturn(2L);
        // Existing participants already use COLORS[0] and COLORS[1]; save() grows the roster exactly
        // like the persistence context would, so the post-save roster query returns the joiner too.
        List<RoomParticipant> roster = new ArrayList<>(List.of(
                participant(JOINER_A_ID, "Alice", ColorPalette.COLORS.get(0)),
                participant(JOINER_B_ID, "Bob", ColorPalette.COLORS.get(1))));
        when(roomParticipantRepository.findActiveByRoomId(ROOM_ID)).thenAnswer(inv -> new ArrayList<>(roster));
        when(roomParticipantRepository.save(any(RoomParticipant.class))).thenAnswer(inv -> {
            roster.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(drawingRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(drawing()));

        RoomJoinResponse response = roomService.joinRoom(ROOM_CODE, new JoinRoomRequest("Carol"));

        assertThat(response.color()).isEqualTo(ColorPalette.COLORS.get(2));
        assertThat(response.participants()).hasSize(3);
        verify(roomParticipantRepository).save(argThat(p ->
                ColorPalette.COLORS.get(2).equals(p.getAssignedColor())
                        && "Carol".equals(p.getDisplayName())
                        && p.getLeftAt() == null));
    }

    // ------------------------------------------------------------------
    // leave (§3 #5, §10)
    // ------------------------------------------------------------------

    @Test
    void leaveWithInvalidTokenThrowsUnauthorized() {
        Room room = activeRoom();
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        when(roomParticipantRepository.findByRoomIdAndParticipantIdAndParticipantToken(
                eq(ROOM_ID), eq(OWNER_ID), eq(OWNER_TOKEN))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.leave(ROOM_CODE,
                new LeaveRoomRequest(OWNER_ID.toString(), OWNER_TOKEN.toString())))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void leaveOwnerTransfersOwnershipToEarliestJoined() {
        Room room = activeRoom();
        Instant joinedEarly = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant joinedLater = Instant.now().minus(5, ChronoUnit.MINUTES);
        RoomParticipant owner = participant(OWNER_ID, "Alice", OWNER_TOKEN, ColorPalette.COLORS.get(0), joinedEarly);
        RoomParticipant earliestRemaining = participant(JOINER_A_ID, "Bob", ColorPalette.COLORS.get(1), joinedLater.minusSeconds(60));
        RoomParticipant laterRemaining = participant(JOINER_B_ID, "Carol", ColorPalette.COLORS.get(2), joinedLater);

        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        when(roomParticipantRepository.findByRoomIdAndParticipantIdAndParticipantToken(
                ROOM_ID, OWNER_ID, OWNER_TOKEN)).thenReturn(Optional.of(owner));
        when(roomParticipantRepository.findActiveByRoomId(ROOM_ID))
                .thenReturn(List.of(earliestRemaining, laterRemaining));

        roomService.leave(ROOM_CODE, new LeaveRoomRequest(OWNER_ID.toString(), OWNER_TOKEN.toString()));

        assertThat(owner.getLeftAt()).isNotNull();
        verify(roomRepository).save(argThat(saved -> JOINER_A_ID.equals(saved.getOwnerParticipantId())));
        verify(roomParticipantRepository).save(owner);
        verify(presenceBroadcaster).broadcastParticipants(ROOM_CODE);
        verify(presenceBroadcaster).broadcastEvent(eq(ROOM_CODE), argThat(notice ->
                "PARTICIPANT_LEFT".equals(notice.type()) && OWNER_ID.toString().equals(notice.participantId())));
        verify(presenceBroadcaster).broadcastEvent(eq(ROOM_CODE), argThat(notice ->
                "OWNER_CHANGED".equals(notice.type()) && JOINER_A_ID.toString().equals(notice.participantId())));
    }

    @Test
    void leaveNonOwnerDoesNotTransferOwnership() {
        Room room = activeRoom();
        RoomParticipant joiner = participant(JOINER_A_ID, "Bob", ColorPalette.COLORS.get(1), Instant.now());
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        when(roomParticipantRepository.findByRoomIdAndParticipantIdAndParticipantToken(
                eq(ROOM_ID), eq(JOINER_A_ID), any(UUID.class))).thenReturn(Optional.of(joiner));

        roomService.leave(ROOM_CODE, new LeaveRoomRequest(JOINER_A_ID.toString(), joiner.getParticipantToken().toString()));

        verify(roomRepository, never()).save(any(Room.class));
        verify(presenceBroadcaster, never()).broadcastEvent(eq(ROOM_CODE), argThat(notice ->
                "OWNER_CHANGED".equals(notice.type())));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Room activeRoom() {
        Room room = new Room(ROOM_CODE, "Test room", OWNER_ID, 5,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(23, ChronoUnit.HOURS), true);
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        return room;
    }

    private Drawing drawing() {
        Drawing drawing = new Drawing("Test room", ROOM_ID, OWNER_ID, Instant.now(), Instant.now());
        ReflectionTestUtils.setField(drawing, "id", UUID.fromString("60000000-0000-0000-0000-000000000006"));
        return drawing;
    }

    private RoomParticipant participant(UUID participantId, String displayName, String color) {
        return participant(participantId, displayName, color, Instant.now());
    }

    private RoomParticipant participant(UUID participantId, String displayName, String color, Instant joinedAt) {
        return participant(participantId, displayName, UUID.randomUUID(), color, joinedAt);
    }

    private RoomParticipant participant(UUID participantId, String displayName, UUID token, String color, Instant joinedAt) {
        return new RoomParticipant(ROOM_ID, participantId, token, displayName, color,
                joinedAt, Instant.now(), null);
    }
}

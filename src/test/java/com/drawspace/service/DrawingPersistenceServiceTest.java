package com.drawspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.drawspace.config.DrawSpaceProperties;
import com.drawspace.dto.AckMessage;
import com.drawspace.dto.DrawingOperationMessage;
import com.drawspace.dto.EventNotice;
import com.drawspace.dto.PointDto;
import com.drawspace.entity.Drawing;
import com.drawspace.entity.DrawingEvent;
import com.drawspace.entity.Room;
import com.drawspace.exception.InvalidOperationException;
import com.drawspace.repository.DrawingEventRepository;
import com.drawspace.repository.DrawingRepository;
import com.drawspace.repository.RoomRepository;
import tools.jackson.databind.ObjectMapper;
/**
 * Unit tests for the save pipeline (CONTRACT §6): sequence assignment, dedupe,
 * validation gates, contributor upsert, snapshot trigger, after-commit broadcast
 * fallback (no tx synchronization active → send immediately).
 */
@ExtendWith(MockitoExtension.class)
class DrawingPersistenceServiceTest {

    private static final String ROOM_CODE = "ABC234";
    private static final UUID ROOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DRAWING_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID PARTICIPANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final String PARTICIPANT_ID_STR = PARTICIPANT_ID.toString();
    private static final String EVENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String DRAW_TOPIC = "/topic/room/ABC234/draw";

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private ParticipantService participantService;
    @Mock
    private DrawingRepository drawingRepository;
    @Mock
    private DrawingEventRepository drawingEventRepository;
    @Mock
    private ContributorService contributorService;
    @Mock
    private SnapshotService snapshotService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private final DrawSpaceProperties properties = new DrawSpaceProperties(
            24, 5, 10, 5, new DrawSpaceProperties.Cors("http://localhost:5173"));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DrawingPersistenceService service;
    private Room room;
    private Drawing drawing;

    @BeforeEach
    void setUp() {
        service = new DrawingPersistenceService(roomRepository, participantService, drawingRepository,
                drawingEventRepository, contributorService, snapshotService, messagingTemplate,
                properties, objectMapper);
        room = new Room(ROOM_CODE, "Test room", PARTICIPANT_ID, 5,
                Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS),
                Instant.now().plus(23, java.time.temporal.ChronoUnit.HOURS), true);
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        drawing = new Drawing("Test room", ROOM_ID, PARTICIPANT_ID, Instant.now(), Instant.now());
        ReflectionTestUtils.setField(drawing, "id", DRAWING_ID);
    }

    // ------------------------------------------------------------------
    // sequence assignment (§6)
    // ------------------------------------------------------------------

    @Test
    void firstOperationGetsSequenceOne() {
        stubHappyPipeline();
        when(drawingEventRepository.findTopByRoomIdOrderBySequenceNumberDesc(ROOM_ID))
                .thenReturn(Optional.empty());

        DrawingOperationMessage result = service.handleOperation(
                ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", brushOp());

        ArgumentCaptor<DrawingEvent> eventCaptor = ArgumentCaptor.forClass(DrawingEvent.class);
        verify(drawingEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getSequenceNumber()).isEqualTo(1L);
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("BRUSH_STROKE");
        assertThat(result.getSequenceNumber()).isEqualTo(1L);
    }

    @Test
    void sequenceIncrementsBeyondMax() {
        stubHappyPipeline();
        DrawingEvent last = new DrawingEvent(UUID.randomUUID(), DRAWING_ID, ROOM_ID, PARTICIPANT_ID,
                41L, "BRUSH_STROKE", Map.of("type", "BRUSH_STROKE"), Instant.now());
        when(drawingEventRepository.findTopByRoomIdOrderBySequenceNumberDesc(ROOM_ID))
                .thenReturn(Optional.of(last));

        DrawingOperationMessage result = service.handleOperation(
                ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", brushOp());

        ArgumentCaptor<DrawingEvent> eventCaptor = ArgumentCaptor.forClass(DrawingEvent.class);
        verify(drawingEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getSequenceNumber()).isEqualTo(42L);
        assertThat(result.getSequenceNumber()).isEqualTo(42L);
    }

    // ------------------------------------------------------------------
    // dedupe (§6: same eventId → existing enriched copy, no re-broadcast)
    // ------------------------------------------------------------------

    @Test
    void duplicateEventIdReturnsExistingSequenceWithoutInsertOrBroadcast() {
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        Instant createdAt = Instant.now().minusSeconds(60);
        DrawingEvent existing = new DrawingEvent(UUID.fromString(EVENT_ID), DRAWING_ID, ROOM_ID, PARTICIPANT_ID,
                7L, "BRUSH_STROKE", Map.of("type", "BRUSH_STROKE"), createdAt);
        when(drawingEventRepository.findByEventId(UUID.fromString(EVENT_ID))).thenReturn(Optional.of(existing));

        DrawingOperationMessage result = service.handleOperation(
                ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", brushOp());

        assertThat(result.getSequenceNumber()).isEqualTo(7L);
        assertThat(result.getServerTimestamp()).isEqualTo(createdAt);
        verify(drawingEventRepository, never()).save(any(DrawingEvent.class));
        verify(drawingEventRepository, never()).findTopByRoomIdOrderBySequenceNumberDesc(any(UUID.class));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(messagingTemplate).convertAndSendToUser(eq(PARTICIPANT_ID_STR), eq("/queue/ack"),
                argThat(ack -> ack instanceof AckMessage a
                        && a.sequenceNumber() == 7L && "SAVED".equals(a.status())));
        verifyNoInteractions(contributorService, snapshotService);
    }

    @Test
    void dataIntegrityViolationRaceTreatedAsDuplicate() {
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        when(roomRepository.findByIdForUpdate(ROOM_ID)).thenReturn(Optional.of(room));
        when(drawingRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(drawing));
        when(drawingEventRepository.findTopByRoomIdOrderBySequenceNumberDesc(ROOM_ID))
                .thenReturn(Optional.empty());
        Instant createdAt = Instant.now().minusSeconds(30);
        DrawingEvent raceWinner = new DrawingEvent(UUID.fromString(EVENT_ID), DRAWING_ID, ROOM_ID, PARTICIPANT_ID,
                9L, "BRUSH_STROKE", Map.of("type", "BRUSH_STROKE"), createdAt);
        when(drawingEventRepository.findByEventId(UUID.fromString(EVENT_ID)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raceWinner));
        when(drawingEventRepository.save(any(DrawingEvent.class)))
                .thenThrow(new DataIntegrityViolationException("uq_drawing_events_event_id"));

        DrawingOperationMessage result = service.handleOperation(
                ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", brushOp());

        assertThat(result.getSequenceNumber()).isEqualTo(9L);
        verify(messagingTemplate).convertAndSendToUser(eq(PARTICIPANT_ID_STR), eq("/queue/ack"),
                argThat(ack -> ack instanceof AckMessage a && a.sequenceNumber() == 9L));
        verify(messagingTemplate, never()).convertAndSend(eq(DRAW_TOPIC), any(Object.class));
        verifyNoInteractions(contributorService);
    }

    // ------------------------------------------------------------------
    // validation gates (§5/§7)
    // ------------------------------------------------------------------

    @Test
    void gestureToolRejectedWithoutAnyPersistence() {
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));

        DrawingOperationMessage op = brushOp();
        op.setTool("SELECT");

        assertThatThrownBy(() -> service.handleOperation(
                ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", op))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("gesture");
        verify(drawingEventRepository, never()).save(any(DrawingEvent.class));
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void oversizedTextRejected() {
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));

        DrawingOperationMessage op = new DrawingOperationMessage();
        op.setEventId(EVENT_ID);
        op.setType("TEXT");
        op.setTool("TEXT");
        op.setLayerId("L1");
        op.setText("a".repeat(2001));

        assertThatThrownBy(() -> service.handleOperation(
                ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", op))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("2000");
        verify(drawingEventRepository, never()).save(any(DrawingEvent.class));
    }

    @Test
    void strokeWithTooManyPointsRejected() {
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));

        DrawingOperationMessage op = brushOp();
        List<PointDto> points = new java.util.ArrayList<>();
        for (int i = 0; i < 4001; i++) {
            points.add(new PointDto(i, i));
        }
        op.setPoints(points);

        assertThatThrownBy(() -> service.handleOperation(
                ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", op))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("4000");
        verify(drawingEventRepository, never()).save(any(DrawingEvent.class));
    }

    // ------------------------------------------------------------------
    // contributor upsert + enrichment (§6/§7)
    // ------------------------------------------------------------------

    @Test
    void contributorUpsertCalledWithTool() {
        stubHappyPipeline();
        when(drawingEventRepository.findTopByRoomIdOrderBySequenceNumberDesc(ROOM_ID))
                .thenReturn(Optional.empty());

        service.handleOperation(ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", brushOp());

        verify(contributorService).onContribution(DRAWING_ID, PARTICIPANT_ID, "Alice", "#EF4444",
                "BRUSH_STROKE", "PENCIL");
        verify(drawingRepository).save(drawing);
        assertThat(drawing.getUpdatedAt()).isNotNull();
    }

    @Test
    void broadcastAndAckSentImmediatelyWithoutTransactionSynchronization() {
        stubHappyPipeline();
        when(drawingEventRepository.findTopByRoomIdOrderBySequenceNumberDesc(ROOM_ID))
                .thenReturn(Optional.empty());

        DrawingOperationMessage result = service.handleOperation(
                ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", brushOp());

        // enrichment (§4/§7): participantId, displayName, participantColor, sequenceNumber, serverTimestamp
        assertThat(result.getParticipantId()).isEqualTo(PARTICIPANT_ID_STR);
        assertThat(result.getDisplayName()).isEqualTo("Alice");
        assertThat(result.getParticipantColor()).isEqualTo("#EF4444");
        assertThat(result.getServerTimestamp()).isNotNull();
        // no tx synchronization active in unit tests → after-commit fallback sends immediately
        verify(messagingTemplate).convertAndSend(eq(DRAW_TOPIC), org.mockito.Mockito.same(result));
        verify(messagingTemplate).convertAndSendToUser(eq(PARTICIPANT_ID_STR), eq("/queue/ack"),
                argThat(ack -> ack instanceof AckMessage a
                        && EVENT_ID.equals(a.eventId()) && "SAVED".equals(a.status())));
    }

    @Test
    void persistedEventDataExcludesEnrichmentFields() {
        stubHappyPipeline();
        when(drawingEventRepository.findTopByRoomIdOrderBySequenceNumberDesc(ROOM_ID))
                .thenReturn(Optional.empty());

        service.handleOperation(ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", brushOp());

        ArgumentCaptor<DrawingEvent> eventCaptor = ArgumentCaptor.forClass(DrawingEvent.class);
        verify(drawingEventRepository).save(eventCaptor.capture());
        Map<String, Object> eventData = eventCaptor.getValue().getEventData();
        assertThat(eventData).doesNotContainKeys(
                "participantId", "displayName", "participantColor", "sequenceNumber", "serverTimestamp");
        assertThat(eventData).containsKeys("eventId", "type", "tool", "color", "points");
    }

    // ------------------------------------------------------------------
    // snapshots (§6/§9)
    // ------------------------------------------------------------------

    @Test
    void snapshotTriggeredAtSnapshotInterval() {
        stubHappyPipeline();
        DrawingEvent last = new DrawingEvent(UUID.randomUUID(), DRAWING_ID, ROOM_ID, PARTICIPANT_ID,
                4L, "BRUSH_STROKE", Map.of("type", "BRUSH_STROKE"), Instant.now());
        when(drawingEventRepository.findTopByRoomIdOrderBySequenceNumberDesc(ROOM_ID))
                .thenReturn(Optional.of(last)); // next sequence = 5 = snapshotInterval

        service.handleOperation(ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", brushOp());

        verify(snapshotService).createSnapshot(DRAWING_ID, ROOM_ID, 5L);
        verify(messagingTemplate).convertAndSend(eq("/topic/room/ABC234/canvas"), (Object) argThat(notice ->
                notice instanceof Map<?, ?> map && "SNAPSHOT_CREATED".equals(map.get("type"))
                        && Long.valueOf(5L).equals(map.get("sequenceNumber"))));
        verify(messagingTemplate).convertAndSend(eq("/topic/room/ABC234/events"), (Object) argThat(notice ->
                notice instanceof EventNotice e && "SNAPSHOT_SAVED".equals(e.type())));
    }

    @Test
    void snapshotNotTriggeredOutsideInterval() {
        stubHappyPipeline();
        when(drawingEventRepository.findTopByRoomIdOrderBySequenceNumberDesc(ROOM_ID))
                .thenReturn(Optional.empty()); // sequence 1 — interval is 5

        service.handleOperation(ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444", brushOp());

        verifyNoInteractions(snapshotService);
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/room/ABC234/canvas"), any(Object.class));
    }

    @Test
    void cursorRelayedToCursorTopicNeverPersisted() {
        service.handleCursor(ROOM_CODE, PARTICIPANT_ID_STR, "Alice", "#EF4444",
                new com.drawspace.dto.CursorMessage(12.5, -3.0));

        verify(messagingTemplate).convertAndSend(eq("/topic/room/ABC234/cursor"), (Object) argThat(payload ->
                payload instanceof Map<?, ?> map
                        && Double.valueOf(12.5).equals(map.get("x"))
                        && "Alice".equals(map.get("displayName"))
                        && "#EF4444".equals(map.get("color"))));
        verifyNoInteractions(drawingEventRepository, drawingRepository, roomRepository);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void stubHappyPipeline() {
        when(roomRepository.findByRoomCode(ROOM_CODE)).thenReturn(Optional.of(room));
        when(roomRepository.findByIdForUpdate(ROOM_ID)).thenReturn(Optional.of(room));
        when(drawingRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(drawing));
        when(drawingEventRepository.save(any(DrawingEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(drawingRepository.save(any(Drawing.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private DrawingOperationMessage brushOp() {
        DrawingOperationMessage op = new DrawingOperationMessage();
        op.setEventId(EVENT_ID);
        op.setType("BRUSH_STROKE");
        op.setTool("PENCIL");
        op.setLayerId("L1");
        op.setColor("#112233");
        op.setBrushSize(4);
        op.setOpacity(0.9);
        op.setPoints(List.of(new PointDto(0, 0), new PointDto(10, 10), new PointDto(20, 5)));
        op.setClientTimestamp(System.currentTimeMillis());
        return op;
    }
}

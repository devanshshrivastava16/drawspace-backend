package com.drawspace.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drawspace.config.DrawSpaceProperties;
import com.drawspace.dto.AckMessage;
import com.drawspace.dto.CursorMessage;
import com.drawspace.dto.DrawingOperationMessage;
import com.drawspace.dto.EventNotice;
import com.drawspace.dto.ViewportMessage;
import com.drawspace.entity.Drawing;
import com.drawspace.entity.DrawingEvent;
import com.drawspace.entity.Room;
import com.drawspace.exception.InvalidOperationException;
import com.drawspace.exception.RoomInactiveException;
import com.drawspace.exception.RoomNotFoundException;
import com.drawspace.exception.UnauthorizedActionException;
import com.drawspace.repository.DrawingEventRepository;
import com.drawspace.repository.DrawingRepository;
import com.drawspace.repository.RoomRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
/**
 * The save pipeline (CONTRACT §6): validate → dedupe by eventId → lock room row →
 * assign sequence → insert drawing_event → upsert contributor → touch drawing →
 * AFTER COMMIT broadcast enriched op + ack (+ periodic snapshot).
 *
 * <p>Idempotency: a retry with the SAME eventId returns the existing enriched copy
 * (ack with the existing sequence) without re-broadcasting or touching contributors.
 * Cursor and viewport messages NEVER enter this pipeline (§6) — they are relayed only.</p>
 */
@Service
public class DrawingPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(DrawingPersistenceService.class);

    /** Enrichment fields stripped before persisting event_data (§7: server adds them, never the client). */
    private static final Set<String> ENRICHMENT_KEYS = Set.of(
            "participantId", "displayName", "participantColor", "sequenceNumber", "serverTimestamp");

    private static final String ACK_QUEUE = "/queue/ack";
    private static final String STATUS_SAVED = "SAVED";

    private final RoomRepository roomRepository;
    private final ParticipantService participantService;
    private final DrawingRepository drawingRepository;
    private final DrawingEventRepository drawingEventRepository;
    private final ContributorService contributorService;
    private final SnapshotService snapshotService;
    private final SimpMessagingTemplate messagingTemplate;
    private final DrawSpaceProperties properties;
    private final ObjectMapper objectMapper;

    public DrawingPersistenceService(RoomRepository roomRepository,
                                     ParticipantService participantService,
                                     DrawingRepository drawingRepository,
                                     DrawingEventRepository drawingEventRepository,
                                     ContributorService contributorService,
                                     SnapshotService snapshotService,
                                     SimpMessagingTemplate messagingTemplate,
                                     DrawSpaceProperties properties,
                                     ObjectMapper objectMapper) {
        this.roomRepository = roomRepository;
        this.participantService = participantService;
        this.drawingRepository = drawingRepository;
        this.drawingEventRepository = drawingEventRepository;
        this.contributorService = contributorService;
        this.snapshotService = snapshotService;
        this.messagingTemplate = messagingTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Persists one drawing operation (§6) and returns the enriched copy (participantId,
     * displayName, participantColor, sequenceNumber, serverTimestamp set).
     *
     * @throws RoomNotFoundException        unknown room code (404)
     * @throws RoomInactiveException        room expired or deactivated (410)
     * @throws UnauthorizedActionException  participant not enrolled / has left (403)
     * @throws InvalidOperationException    invalid op (400)
     */
    @Transactional
    public DrawingOperationMessage handleOperation(String roomCode, String participantId, String displayName,
                                                   String participantColor, DrawingOperationMessage op) {
        if (op == null) {
            throw new InvalidOperationException("Drawing operation payload is required");
        }
        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new RoomNotFoundException("Room not found: " + roomCode));
        Instant now = Instant.now();
        if (!room.isActive() || room.getExpiresAt() == null || room.getExpiresAt().isBefore(now)) {
            throw new RoomInactiveException("Room " + roomCode + " is expired or inactive");
        }
        UUID participantUuid = parseUuid(participantId);
        if (participantUuid == null) {
            throw new UnauthorizedActionException("Invalid participant identity");
        }
        participantService.assertActiveParticipant(room, participantUuid);

        OperationValidator.validate(op);

        UUID eventId = UUID.fromString(op.getEventId().trim());
        Optional<DrawingEvent> existing = drawingEventRepository.findByEventId(eventId);
        if (existing.isPresent()) {
            log.debug("Duplicate drawing event {} in room {} — acknowledging existing sequence {}",
                    eventId, roomCode, existing.get().getSequenceNumber());
            return enrichDuplicate(op, displayName, participantColor, existing.get());
        }

        // Concurrency: lock the room row BEFORE reading max(sequence) and inserting (§10)
        roomRepository.findByIdForUpdate(room.getId())
                .orElseThrow(() -> new RoomNotFoundException("Room not found: " + roomCode));
        Drawing drawing = drawingRepository.findByRoomId(room.getId())
                .orElseThrow(() -> new IllegalStateException("Drawing not found for room " + roomCode));

        long sequence = drawingEventRepository.findTopByRoomIdOrderBySequenceNumberDesc(room.getId())
                .map(DrawingEvent::getSequenceNumber)
                .orElse(0L) + 1;

        Map<String, Object> eventData = toEventData(op);
        DrawingEvent event = new DrawingEvent(eventId, drawing.getId(), room.getId(), participantUuid,
                sequence, op.getType(), eventData, now);
        try {
            drawingEventRepository.save(event);
        } catch (DataIntegrityViolationException ex) {
            // Rare race on dedupe (§6): another writer persisted the same eventId first.
            DrawingEvent raceWinner = drawingEventRepository.findByEventId(eventId).orElse(null);
            if (raceWinner != null) {
                log.warn("Race on eventId {} in room {} — treating as duplicate of sequence {}",
                        eventId, roomCode, raceWinner.getSequenceNumber());
                return enrichDuplicate(op, displayName, participantColor, raceWinner);
            }
            throw ex;
        }

        contributorService.onContribution(drawing.getId(), participantUuid, displayName,
                participantColor, op.getType(), op.getTool());
        drawing.setUpdatedAt(now);
        drawingRepository.save(drawing);

        op.setParticipantId(participantId);
        op.setDisplayName(displayName);
        op.setParticipantColor(participantColor);
        op.setSequenceNumber(sequence);
        op.setServerTimestamp(now);

        final UUID drawingId = drawing.getId();
        final long snapshotInterval = properties.snapshotInterval();
        final long eventSequence = sequence;
        final String code = roomCode;
        PresenceBroadcaster.runAfterCommit(() -> {
            broadcastEnriched(code, op);
            messagingTemplate.convertAndSendToUser(participantId, ACK_QUEUE,
                    new AckMessage(op.getEventId(), eventSequence, STATUS_SAVED));
            if (snapshotInterval > 0 && eventSequence % snapshotInterval == 0) {
                createSnapshotAfterCommit(code, drawingId, room.getId(), participantId, displayName, eventSequence);
            }
        });
        return op;
    }

    /** AFTER-COMMIT snapshot creation; failure is logged and never fails the op (§6). */
    private void createSnapshotAfterCommit(String roomCode, UUID drawingId, UUID roomId,
                                           String participantId, String displayName, long sequence) {
        try {
            snapshotService.createSnapshot(drawingId, roomId, sequence);
            Map<String, Object> canvasNotice = new LinkedHashMap<>();
            canvasNotice.put("type", "SNAPSHOT_CREATED");
            canvasNotice.put("sequenceNumber", sequence);
            messagingTemplate.convertAndSend(PresenceBroadcaster.roomTopic(roomCode, "canvas"), (Object) canvasNotice);
            messagingTemplate.convertAndSend(PresenceBroadcaster.roomTopic(roomCode, PresenceBroadcaster.TOPIC_EVENTS),
                    new EventNotice("SNAPSHOT_SAVED", participantId, displayName,
                            "Snapshot saved (sequence " + sequence + ")", Instant.now()));
        } catch (Exception ex) {
            log.error("Snapshot creation failed for drawing {} at sequence {}", drawingId, sequence, ex);
        }
    }

    /** Broadcasts the enriched accepted op to /topic/room/{code}/draw (§4). */
    public void broadcastEnriched(String roomCode, DrawingOperationMessage op) {
        messagingTemplate.convertAndSend(PresenceBroadcaster.roomTopic(roomCode, "draw"), op);
    }

    private DrawingOperationMessage enrichDuplicate(DrawingOperationMessage op, String displayName,
                                                    String participantColor, DrawingEvent existing) {
        op.setParticipantId(existing.getParticipantId().toString());
        op.setDisplayName(displayName);
        op.setParticipantColor(participantColor);
        op.setSequenceNumber(existing.getSequenceNumber());
        op.setServerTimestamp(existing.getCreatedAt());
        // No re-broadcast, no contributor update — only the ack with the existing sequence (§6).
        messagingTemplate.convertAndSendToUser(existing.getParticipantId().toString(), ACK_QUEUE,
                new AckMessage(op.getEventId(), existing.getSequenceNumber(), STATUS_SAVED));
        return op;
    }

    /**
     * Relays a cursor position to /topic/room/{code}/cursor (§4). NEVER persisted.
     * {@code color} is the participant's palette color (cursor dot), not a stroke color.
     */
    public void handleCursor(String roomCode, String participantId, String displayName,
                             String color, CursorMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("participantId", participantId);
        payload.put("displayName", displayName);
        payload.put("color", color);
        payload.put("x", message.x());
        payload.put("y", message.y());
        messagingTemplate.convertAndSend(PresenceBroadcaster.roomTopic(roomCode, "cursor"), (Object) payload);
    }

    /** Relays a viewport (minimap indicator) to /topic/room/{code}/viewport (§4). NEVER persisted. */
    public void handleViewport(String roomCode, String participantId, String displayName,
                               String color, ViewportMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("participantId", participantId);
        payload.put("displayName", displayName);
        payload.put("color", color);
        payload.put("x", message.x());
        payload.put("y", message.y());
        payload.put("zoom", message.zoom());
        payload.put("width", message.width());
        payload.put("height", message.height());
        messagingTemplate.convertAndSend(PresenceBroadcaster.roomTopic(roomCode, "viewport"), (Object) payload);
    }

    /**
     * Converts the op POJO to the persisted {@code event_data} map (§7): the full field
     * map as JSON, minus the server-only enrichment fields and null values.
     */
    private Map<String, Object> toEventData(DrawingOperationMessage op) {
        Map<String, Object> data = objectMapper.convertValue(
                op,
                new TypeReference<Map<String, Object>>() {}
        );
        data.keySet().removeIf(ENRICHMENT_KEYS::contains);
        data.values().removeIf(Objects::isNull);
        return data;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

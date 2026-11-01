package com.drawspace.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drawspace.config.DrawSpaceProperties;
import com.drawspace.domain.EventType;
import com.drawspace.dto.CanvasAuthRequest;
import com.drawspace.dto.CreateRoomRequest;
import com.drawspace.dto.DrawingOperationMessage;
import com.drawspace.dto.EventNotice;
import com.drawspace.dto.JoinRoomRequest;
import com.drawspace.dto.LeaveRoomRequest;
import com.drawspace.dto.ParticipantResponse;
import com.drawspace.dto.RoomDetailsResponse;
import com.drawspace.dto.RoomJoinResponse;
import com.drawspace.entity.Drawing;
import com.drawspace.entity.Room;
import com.drawspace.entity.RoomParticipant;
import com.drawspace.exception.InvalidTokenException;
import com.drawspace.exception.RoomFullException;
import com.drawspace.exception.RoomInactiveException;
import com.drawspace.exception.RoomNotFoundException;
import com.drawspace.exception.UnauthorizedActionException;
import com.drawspace.repository.DrawingRepository;
import com.drawspace.repository.RoomParticipantRepository;
import com.drawspace.repository.RoomRepository;
import com.drawspace.util.ColorPalette;
import com.drawspace.util.RoomCodes;

/**
 * Room lifecycle (CONTRACT §3 #1–#5, §10): create/join with capacity enforcement under
 * a pessimistic room-row lock, leave with owner transfer, details/participants queries,
 * and the owner-only canvas clear (persisted as a CLEAR op through the save pipeline).
 */
@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private static final int MAX_CODE_GENERATION_ATTEMPTS = 10;

    private final RoomRepository roomRepository;
    private final DrawingRepository drawingRepository;
    private final RoomParticipantRepository roomParticipantRepository;
    private final ParticipantService participantService;
    private final PresenceBroadcaster presenceBroadcaster;
    private final DrawSpaceProperties properties;
    private final DrawingPersistenceService drawingPersistenceService;

    public RoomService(RoomRepository roomRepository,
                       DrawingRepository drawingRepository,
                       RoomParticipantRepository roomParticipantRepository,
                       ParticipantService participantService,
                       PresenceBroadcaster presenceBroadcaster,
                       DrawSpaceProperties properties,
                       DrawingPersistenceService drawingPersistenceService) {
        this.roomRepository = roomRepository;
        this.drawingRepository = drawingRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.participantService = participantService;
        this.presenceBroadcaster = presenceBroadcaster;
        this.properties = properties;
        this.drawingPersistenceService = drawingPersistenceService;
    }

    /** §3 #1 — creates room + drawing + owner participant; owner gets the first palette color. */
    @Transactional
    public RoomJoinResponse createRoom(CreateRoomRequest request) {
        Instant now = Instant.now();
        String roomCode = generateUniqueCode();
        UUID ownerParticipantId = UUID.randomUUID();
        UUID participantToken = UUID.randomUUID();

        Room room = roomRepository.save(new Room(
                roomCode,
                request.roomTitle(),
                ownerParticipantId,
                properties.maxParticipants(),
                now,
                now.plus(properties.roomTtlHours(), ChronoUnit.HOURS),
                true));

        String drawingTitle = request.roomTitle() == null || request.roomTitle().isBlank()
                ? "Untitled Drawing"
                : request.roomTitle();
        Drawing drawing = drawingRepository.save(new Drawing(
                drawingTitle, room.getId(), ownerParticipantId, now, now));

        RoomParticipant owner = roomParticipantRepository.save(new RoomParticipant(
                room.getId(), ownerParticipantId, participantToken,
                request.displayName().trim(), ColorPalette.COLORS.get(0), now, now, null));

        List<ParticipantResponse> participants = List.of(
                participantService.toParticipantResponse(owner, ownerParticipantId));
        log.info("Created room {} (drawing {})", room.getRoomCode(), drawing.getId());
        return new RoomJoinResponse(room.getRoomCode(), request.roomTitle(), drawing.getId(),
                owner.getParticipantId(), participantToken.toString(), owner.getDisplayName(),
                owner.getAssignedColor(), room.getMaxParticipants(), participants);
    }

    /**
     * §3 #2 — join with capacity enforcement: the room row is locked (PESSIMISTIC_WRITE)
     * BEFORE counting active participants and inserting, so concurrent joins serialize (§10).
     * Broadcasts the new roster + PARTICIPANT_JOINED after commit.
     */
    @Transactional
    public RoomJoinResponse joinRoom(String roomCode, JoinRoomRequest request) {
        Room existing = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new RoomNotFoundException("Room not found: " + roomCode));
        if (!existing.isActive() || existing.getExpiresAt() == null
                || existing.getExpiresAt().isBefore(Instant.now())) {
            throw new RoomInactiveException("Room " + roomCode + " is expired or inactive");
        }
        // Concurrency-critical section (§10): lock the row BEFORE counting seats and inserting,
        // so concurrent joins serialize on the room row.
        Room room = roomRepository.findByIdForUpdate(existing.getId())
                .orElseThrow(() -> new RoomNotFoundException("Room not found: " + roomCode));

        Instant now = Instant.now();
        Instant staleThreshold = now.minus(properties.staleThresholdMinutes(), ChronoUnit.MINUTES);
        long activeCount = roomParticipantRepository.countActiveByRoomId(room.getId(), staleThreshold);
        if (activeCount >= room.getMaxParticipants()) {
            throw new RoomFullException("Room " + roomCode + " is full ("
                    + room.getMaxParticipants() + " active participants)", room.getMaxParticipants());
        }

        String assignedColor = participantService.assignColor(room.getId(), (int) activeCount);
        UUID participantId = UUID.randomUUID();
        UUID participantToken = UUID.randomUUID();
        RoomParticipant participant = roomParticipantRepository.save(new RoomParticipant(
                room.getId(), participantId, participantToken,
                request.displayName().trim(), assignedColor, now, now, null));

        Drawing drawing = drawingRepository.findByRoomId(room.getId())
                .orElseThrow(() -> new IllegalStateException("Drawing not found for room " + roomCode));
        List<ParticipantResponse> participants = roomParticipantRepository.findActiveByRoomId(room.getId())
                .stream()
                .map(p -> participantService.toParticipantResponse(p, room.getOwnerParticipantId()))
                .toList();

        final String code = room.getRoomCode();
        final String joinedName = participant.getDisplayName();
        final String joinedId = participantId.toString();
        PresenceBroadcaster.runAfterCommit(() -> {
            presenceBroadcaster.broadcastParticipants(code);
            presenceBroadcaster.broadcastEvent(code, new EventNotice("PARTICIPANT_JOINED", joinedId,
                    joinedName, joinedName + " joined the room", Instant.now()));
        });
        log.info("Participant {} joined room {}", joinedId, code);
        return new RoomJoinResponse(room.getRoomCode(), room.getTitle(), drawing.getId(),
                participant.getParticipantId(), participantToken.toString(), participant.getDisplayName(),
                participant.getAssignedColor(), room.getMaxParticipants(), participants);
    }

    /**
     * §3 #5 — leave: token verified (401 on mismatch), seat freed (left_at set);
     * if the owner leaves, ownership transfers to the earliest-joined remaining
     * active participant + OWNER_CHANGED notice (§10). Idempotent for already-left.
     */
    @Transactional
    public void leave(String roomCode, LeaveRoomRequest request) {
        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new RoomNotFoundException("Room not found: " + roomCode));
        UUID participantId = parseUuid(request.participantId())
                .orElseThrow(() -> new InvalidTokenException("participantId must be a valid UUID"));
        UUID token = parseUuid(request.participantToken())
                .orElseThrow(() -> new InvalidTokenException("participantToken must be a valid UUID"));
        RoomParticipant participant = roomParticipantRepository
                .findByRoomIdAndParticipantIdAndParticipantToken(room.getId(), participantId, token)
                .orElseThrow(() -> new InvalidTokenException("Invalid participant token for this room"));
        if (participant.getLeftAt() != null) {
            return; // already left — idempotent
        }

        Instant now = Instant.now();
        participant.setLeftAt(now);
        roomParticipantRepository.save(participant);

        UUID newOwnerId = null;
        String newOwnerName = null;
        if (room.getOwnerParticipantId().equals(participantId)) {
            List<RoomParticipant> remaining = roomParticipantRepository.findActiveByRoomId(room.getId());
            if (!remaining.isEmpty()) {
                RoomParticipant newOwner = remaining.get(0); // earliest joined (query ordered by joined_at)
                room.setOwnerParticipantId(newOwner.getParticipantId());
                roomRepository.save(room);
                newOwnerId = newOwner.getParticipantId();
                newOwnerName = newOwner.getDisplayName();
            }
        }

        final String code = room.getRoomCode();
        final String leaverName = participant.getDisplayName();
        final String leaverId = participantId.toString();
        final UUID ownerAfter = newOwnerId;
        final String ownerNameAfter = newOwnerName;
        PresenceBroadcaster.runAfterCommit(() -> {
            presenceBroadcaster.broadcastParticipants(code);
            presenceBroadcaster.broadcastEvent(code, new EventNotice("PARTICIPANT_LEFT", leaverId,
                    leaverName, leaverName + " left the room", Instant.now()));
            if (ownerAfter != null) {
                presenceBroadcaster.broadcastEvent(code, new EventNotice("OWNER_CHANGED",
                        ownerAfter.toString(), ownerNameAfter,
                        ownerNameAfter + " is now the room owner", Instant.now()));
            }
        });
        log.info("Participant {} left room {}", leaverId, code);
    }

    /** §3 #3 — room details. */
    @Transactional(readOnly = true)
    public RoomDetailsResponse getRoomDetails(String roomCode) {
        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new RoomNotFoundException("Room not found: " + roomCode));
        Instant now = Instant.now();
        UUID drawingId = drawingRepository.findByRoomId(room.getId()).map(Drawing::getId).orElse(null);
        long activeCount = roomParticipantRepository.countActiveByRoomId(room.getId(),
                now.minus(properties.staleThresholdMinutes(), ChronoUnit.MINUTES));
        boolean effectivelyActive = room.isActive() && room.getExpiresAt() != null && room.getExpiresAt().isAfter(now);
        return new RoomDetailsResponse(room.getRoomCode(), room.getTitle(), drawingId,
                room.getOwnerParticipantId(), room.getMaxParticipants(), activeCount,
                effectivelyActive, room.getCreatedAt());
    }

    /** §3 #4 — active participants (enrolled, not left; earliest joiner first). */
    @Transactional(readOnly = true)
    public List<ParticipantResponse> getActiveParticipants(String roomCode) {
        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new RoomNotFoundException("Room not found: " + roomCode));
        return roomParticipantRepository.findActiveByRoomId(room.getId())
                .stream()
                .map(p -> participantService.toParticipantResponse(p, room.getOwnerParticipantId()))
                .toList();
    }

    /**
     * §3 #10 — owner-only canvas clear: verifies identity + token + ownership, then
     * persists a CLEAR op through the standard save pipeline (§6).
     */
    public DrawingOperationMessage clearCanvas(String roomCode, CanvasAuthRequest request) {
        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new RoomNotFoundException("Room not found: " + roomCode));
        UUID participantId = parseUuid(request.participantId())
                .orElseThrow(() -> new InvalidTokenException("participantId must be a valid UUID"));
        UUID token = parseUuid(request.participantToken())
                .orElseThrow(() -> new InvalidTokenException("participantToken must be a valid UUID"));
        RoomParticipant participant = roomParticipantRepository
                .findByRoomIdAndParticipantIdAndParticipantToken(room.getId(), participantId, token)
                .orElseThrow(() -> new InvalidTokenException("Invalid participant token for this room"));
        if (participant.getLeftAt() != null) {
            throw new UnauthorizedActionException("Participant is not active in this room");
        }
        if (!room.getOwnerParticipantId().equals(participantId)) {
            throw new UnauthorizedActionException("Only the room owner can clear the canvas");
        }
        DrawingOperationMessage op = new DrawingOperationMessage();
        op.setEventId(UUID.randomUUID().toString());
        op.setType(EventType.CLEAR.name());
        op.setClientTimestamp(System.currentTimeMillis());
        return drawingPersistenceService.handleOperation(roomCode, participantId.toString(),
                participant.getDisplayName(), participant.getAssignedColor(), op);
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
            String candidate = RoomCodes.generate();
            if (!roomRepository.existsByRoomCode(candidate)) {
                return candidate;
            }
            log.debug("Room code collision on attempt {}", attempt + 1);
        }
        throw new IllegalStateException(
                "Could not generate a unique room code after " + MAX_CODE_GENERATION_ATTEMPTS + " attempts");
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

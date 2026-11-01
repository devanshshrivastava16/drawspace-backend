package com.drawspace.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.drawspace.config.DrawSpaceProperties;
import com.drawspace.dto.ParticipantResponse;
import com.drawspace.entity.Room;
import com.drawspace.entity.RoomParticipant;
import com.drawspace.exception.UnauthorizedActionException;
import com.drawspace.repository.RoomParticipantRepository;
import com.drawspace.repository.RoomRepository;
import com.drawspace.util.ColorPalette;

/**
 * Participant identity, presence and color assignment (CONTRACT §1/§2/§10).
 *
 * <p>Also backs the {@link com.drawspace.config.RoomParticipantAuthenticator} bean
 * (see {@code config.ParticipantAuthConfig}): {@link #authenticate(String, String)}
 * verifies room + token on every STOMP CONNECT.</p>
 */
@Service
public class ParticipantService {

    private static final Logger log = LoggerFactory.getLogger(ParticipantService.class);

    private final RoomRepository roomRepository;
    private final RoomParticipantRepository roomParticipantRepository;
    private final DrawSpaceProperties properties;

    public ParticipantService(RoomRepository roomRepository,
                              RoomParticipantRepository roomParticipantRepository,
                              DrawSpaceProperties properties) {
        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.properties = properties;
    }

    /**
     * Authenticates a STOMP CONNECT (CONTRACT §1): the room must exist, be active and
     * unexpired, and the token must belong to an enrolled (not-left) participant.
     *
     * @return the participantId (UUID string), or {@code null} when authentication fails
     */
    public String authenticate(String roomCode, String participantToken) {
        if (roomCode == null || participantToken == null || participantToken.isBlank()) {
            return null;
        }
        Optional<Room> room = roomRepository.findByRoomCode(roomCode.trim());
        if (room.isEmpty() || !room.get().isActive()) {
            return null;
        }
        Room found = room.get();
        if (found.getExpiresAt() != null && found.getExpiresAt().isBefore(Instant.now())) {
            return null;
        }
        UUID token;
        try {
            token = UUID.fromString(participantToken.trim());
        } catch (IllegalArgumentException e) {
            log.debug("WS auth rejected: malformed participant token");
            return null;
        }
        return roomParticipantRepository.findActiveByRoomId(found.getId()).stream()
                .filter(p -> token.equals(p.getParticipantToken()))
                .map(p -> p.getParticipantId().toString())
                .findFirst()
                .orElse(null);
    }

    /**
     * Asserts the participant is enrolled in the room and has not left (§6 "participant
     * active" check). Throws {@link UnauthorizedActionException} (→ HTTP 403) otherwise.
     */
    public void assertActiveParticipant(Room room, UUID participantId) {
        RoomParticipant participant = roomParticipantRepository
                .findByRoomIdAndParticipantId(room.getId(), participantId)
                .orElseThrow(() -> new UnauthorizedActionException(
                        "Participant " + participantId + " is not enrolled in room " + room.getRoomCode()));
        if (participant.getLeftAt() != null) {
            throw new UnauthorizedActionException(
                    "Participant " + participantId + " has left room " + room.getRoomCode());
        }
    }

    /** Heartbeat handler (§4 /presence): refreshes last_active_at, keeping the seat non-stale. */
    public void touchPresence(UUID roomId, UUID participantId) {
        roomParticipantRepository.findByRoomIdAndParticipantId(roomId, participantId).ifPresent(participant -> {
            participant.setLastActiveAt(Instant.now());
            roomParticipantRepository.save(participant);
        });
    }

    /**
     * Assigns the first palette color not used by current participants of the room,
     * else index = activeCount % 10 (CONTRACT §2).
     */
    public String assignColor(UUID roomId, int activeCount) {
        List<String> usedColors = roomParticipantRepository.findActiveByRoomId(roomId).stream()
                .map(RoomParticipant::getAssignedColor)
                .toList();
        return ColorPalette.pickColor(usedColors, activeCount);
    }

    /** Maps a participant row to its {@link ParticipantResponse} projection (§3 #4). */
    public ParticipantResponse toParticipantResponse(RoomParticipant participant, UUID ownerParticipantId) {
        boolean isOwner = participant.getParticipantId().equals(ownerParticipantId);
        boolean online = participant.getLeftAt() == null
                && participant.getLastActiveAt() != null
                && !participant.getLastActiveAt()
                        .isBefore(Instant.now().minus(properties.staleThresholdMinutes(), ChronoUnit.MINUTES));
        return new ParticipantResponse(
                participant.getParticipantId(),
                participant.getDisplayName(),
                participant.getAssignedColor(),
                isOwner,
                online,
                participant.getJoinedAt());
    }
}

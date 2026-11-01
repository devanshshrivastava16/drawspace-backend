package com.drawspace.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.drawspace.config.DrawSpaceProperties;
import com.drawspace.dto.EventNotice;
import com.drawspace.entity.Room;
import com.drawspace.entity.RoomParticipant;
import com.drawspace.repository.RoomParticipantRepository;
import com.drawspace.repository.RoomRepository;

/**
 * 60s reaper (CONTRACT §10):
 * <ul>
 *   <li>stale participants (enrolled, no heartbeat for STALE_THRESHOLD_MINUTES) get
 *       {@code left_at} set — freeing their capacity seat — plus per-room roster and
 *       PARTICIPANT_LEFT broadcasts (the WebSocket seat itself stays until disconnect,
 *       but stale cleanup is what frees capacity);</li>
 *   <li>expired rooms (expires_at &lt; now, still active) are deactivated.</li>
 * </ul>
 */
@Component
public class RoomCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RoomCleanupScheduler.class);

    private final RoomRepository roomRepository;
    private final RoomParticipantRepository roomParticipantRepository;
    private final PresenceBroadcaster presenceBroadcaster;
    private final DrawSpaceProperties properties;

    public RoomCleanupScheduler(RoomRepository roomRepository,
                                RoomParticipantRepository roomParticipantRepository,
                                PresenceBroadcaster presenceBroadcaster,
                                DrawSpaceProperties properties) {
        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.presenceBroadcaster = presenceBroadcaster;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        Instant now = Instant.now();
        markStaleParticipants(now);
        deactivateExpiredRooms(now);
    }

    private void markStaleParticipants(Instant now) {
        Instant cutoff = now.minus(properties.staleThresholdMinutes(), ChronoUnit.MINUTES);
        List<RoomParticipant> stale = roomParticipantRepository.findAllStale(cutoff);
        if (stale.isEmpty()) {
            return;
        }
        Map<java.util.UUID, List<RoomParticipant>> byRoom = stale.stream()
                .collect(Collectors.groupingBy(RoomParticipant::getRoomId));
        byRoom.forEach((roomId, participants) -> {
            Optional<Room> room = roomRepository.findById(roomId);
            if (room.isEmpty()) {
                return; // room deleted (cascade removed participants)
            }
            String roomCode = room.get().getRoomCode();
            for (RoomParticipant participant : participants) {
                if (participant.getLeftAt() != null) {
                    continue;
                }
                participant.setLeftAt(now);
                roomParticipantRepository.save(participant);
                presenceBroadcaster.broadcastEvent(roomCode, new EventNotice("PARTICIPANT_LEFT",
                        participant.getParticipantId().toString(), participant.getDisplayName(),
                        participant.getDisplayName() + " left (inactivity)", now));
            }
            presenceBroadcaster.broadcastParticipants(roomCode);
            log.info("Marked {} stale participant(s) as left in room {}", participants.size(), roomCode);
        });
    }

    private void deactivateExpiredRooms(Instant now) {
        for (Room room : roomRepository.findAll()) {
            if (room.isActive() && room.getExpiresAt() != null && room.getExpiresAt().isBefore(now)) {
                room.setActive(false);
                roomRepository.save(room);
                log.info("Deactivated expired room {}", room.getRoomCode());
            }
        }
    }
}

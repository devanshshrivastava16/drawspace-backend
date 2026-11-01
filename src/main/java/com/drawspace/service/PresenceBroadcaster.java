package com.drawspace.service;

import java.util.List;
import java.util.Optional;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.drawspace.dto.EventNotice;
import com.drawspace.dto.ParticipantResponse;
import com.drawspace.entity.Room;
import com.drawspace.entity.RoomParticipant;
import com.drawspace.repository.RoomParticipantRepository;
import com.drawspace.repository.RoomRepository;

/**
 * Broadcasts participant rosters and lifecycle notices over STOMP (CONTRACT §4):
 * <ul>
 *   <li>{@code /topic/room/{code}/participants} — full [ParticipantResponse]</li>
 *   <li>{@code /topic/room/{code}/events} — EventNotice (PARTICIPANT_JOINED | PARTICIPANT_LEFT
 *       | OWNER_CHANGED | SNAPSHOT_SAVED)</li>
 * </ul>
 * Used by RoomService (after-commit), WebSocketEventListener and RoomCleanupScheduler.
 */
@Component
public class PresenceBroadcaster {

    public static final String TOPIC_PARTICIPANTS = "participants";
    public static final String TOPIC_EVENTS = "events";

    private final SimpMessagingTemplate messagingTemplate;
    private final RoomRepository roomRepository;
    private final RoomParticipantRepository roomParticipantRepository;
    private final ParticipantService participantService;

    public PresenceBroadcaster(SimpMessagingTemplate messagingTemplate,
                               RoomRepository roomRepository,
                               RoomParticipantRepository roomParticipantRepository,
                               ParticipantService participantService) {
        this.messagingTemplate = messagingTemplate;
        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.participantService = participantService;
    }

    /** Builds a room broadcast topic, e.g. {@code /topic/room/ABC234/draw}. */
    public static String roomTopic(String roomCode, String leaf) {
        return "/topic/room/" + roomCode + "/" + leaf;
    }

    /** Broadcasts the full active-participant roster for a room (no-op if the room is gone). */
    public void broadcastParticipants(String roomCode) {
        Optional<Room> room = roomRepository.findByRoomCode(roomCode);
        if (room.isEmpty()) {
            return;
        }
        List<ParticipantResponse> participants = roomParticipantRepository
                .findActiveByRoomId(room.get().getId())
                .stream()
                .map(p -> participantService.toParticipantResponse(p, room.get().getOwnerParticipantId()))
                .toList();
        messagingTemplate.convertAndSend(roomTopic(roomCode, TOPIC_PARTICIPANTS), participants);
    }

    /** Broadcasts a lifecycle notice on /topic/room/{code}/events. */
    public void broadcastEvent(String roomCode, EventNotice notice) {
        messagingTemplate.convertAndSend(roomTopic(roomCode, TOPIC_EVENTS), notice);
    }

    /**
     * Runs {@code action} after the current transaction commits; when no transaction
     * synchronization is active (e.g. unit tests, non-transactional callers) the action
     * runs immediately as a fallback.
     */
    public static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}

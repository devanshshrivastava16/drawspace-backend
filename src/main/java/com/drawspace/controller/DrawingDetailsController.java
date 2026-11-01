package com.drawspace.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.drawspace.dto.ContributorListResponse;
import com.drawspace.dto.DrawingDetailsResponse;
import com.drawspace.entity.Drawing;
import com.drawspace.entity.Room;
import com.drawspace.entity.RoomParticipant;
import com.drawspace.exception.RoomNotFoundException;
import com.drawspace.repository.DrawingContributorRepository;
import com.drawspace.repository.DrawingEventRepository;
import com.drawspace.repository.DrawingRepository;
import com.drawspace.repository.RoomParticipantRepository;
import com.drawspace.repository.RoomRepository;
import com.drawspace.service.ContributorService;

/**
 * Drawing metadata API (CONTRACT §3 #6, #7): details (title, room code, creator display
 * name, operation counters) and the contributor leaderboard.
 */
@RestController
@RequestMapping("/api/drawings")
public class DrawingDetailsController {

    private static final String UNKNOWN_ARTIST = "Unknown artist";

    private final DrawingRepository drawingRepository;
    private final RoomRepository roomRepository;
    private final RoomParticipantRepository roomParticipantRepository;
    private final DrawingContributorRepository drawingContributorRepository;
    private final DrawingEventRepository drawingEventRepository;
    private final ContributorService contributorService;

    public DrawingDetailsController(DrawingRepository drawingRepository,
                                    RoomRepository roomRepository,
                                    RoomParticipantRepository roomParticipantRepository,
                                    DrawingContributorRepository drawingContributorRepository,
                                    DrawingEventRepository drawingEventRepository,
                                    ContributorService contributorService) {
        this.drawingRepository = drawingRepository;
        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.drawingContributorRepository = drawingContributorRepository;
        this.drawingEventRepository = drawingEventRepository;
        this.contributorService = contributorService;
    }

    /**
     * §3 #6 — drawing details. The creator's display name is resolved from
     * drawing_contributors first, then from the stored room_participants record
     * (regardless of left_at — the creator may have left); "Unknown artist" only when
     * truly missing.
     */
    @GetMapping("/{drawingId}")
    public DrawingDetailsResponse getDrawingDetails(@PathVariable UUID drawingId) {
        Drawing drawing = drawingRepository.findById(drawingId)
                .orElseThrow(() -> new RoomNotFoundException("Drawing not found: " + drawingId));
        String roomCode = roomRepository.findById(drawing.getRoomId())
                .map(Room::getRoomCode)
                .orElse(null);
        String creatorDisplayName = resolveDisplayName(drawing.getId(), drawing.getRoomId(),
                drawing.getCreatedByParticipantId());
        long totalOperations = drawingEventRepository.countByDrawingId(drawingId);
        long contributorCount = drawingContributorRepository
                .findByDrawingIdOrderByTotalOperationsDesc(drawingId)
                .size();
        return new DrawingDetailsResponse(
                drawing.getId(),
                drawing.getTitle(),
                roomCode,
                drawing.getCreatedByParticipantId(),
                creatorDisplayName,
                drawing.getCreatedAt(),
                drawing.getUpdatedAt(),
                totalOperations,
                contributorCount);
    }

    /** §3 #7 — contributor stats sorted by totalOperations desc. */
    @GetMapping("/{drawingId}/contributors")
    public ContributorListResponse getContributors(@PathVariable UUID drawingId) {
        drawingRepository.findById(drawingId)
                .orElseThrow(() -> new RoomNotFoundException("Drawing not found: " + drawingId));
        return contributorService.getContributorStats(drawingId);
    }

    private String resolveDisplayName(UUID drawingId, UUID roomId, UUID participantId) {
        return drawingContributorRepository.findByDrawingIdAndParticipantId(drawingId, participantId)
                .map(contributor -> contributor.getDisplayName())
                .or(() -> roomParticipantRepository.findByRoomIdAndParticipantId(roomId, participantId)
                        .map(RoomParticipant::getDisplayName))
                .orElse(UNKNOWN_ARTIST);
    }
}

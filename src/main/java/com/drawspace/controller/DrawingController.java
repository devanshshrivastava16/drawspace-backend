package com.drawspace.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.drawspace.dto.DrawingEventResponse;
import com.drawspace.dto.HistoryResponse;
import com.drawspace.dto.SnapshotResponse;
import com.drawspace.entity.Drawing;
import com.drawspace.entity.DrawingContributor;
import com.drawspace.entity.DrawingEvent;
import com.drawspace.exception.RoomNotFoundException;
import com.drawspace.repository.DrawingContributorRepository;
import com.drawspace.repository.DrawingEventRepository;
import com.drawspace.repository.DrawingRepository;
import com.drawspace.service.SnapshotService;

/**
 * Drawing read API (CONTRACT §3 #8, #9): paged event history (replay source, §13)
 * and the latest canvas snapshot.
 */
@RestController
@RequestMapping("/api/drawings")
public class DrawingController {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 1000;
    private static final String UNKNOWN_ARTIST = "Unknown artist";

    private final DrawingRepository drawingRepository;
    private final DrawingEventRepository drawingEventRepository;
    private final DrawingContributorRepository drawingContributorRepository;
    private final SnapshotService snapshotService;

    public DrawingController(DrawingRepository drawingRepository,
                             DrawingEventRepository drawingEventRepository,
                             DrawingContributorRepository drawingContributorRepository,
                             SnapshotService snapshotService) {
        this.drawingRepository = drawingRepository;
        this.drawingEventRepository = drawingEventRepository;
        this.drawingContributorRepository = drawingContributorRepository;
        this.snapshotService = snapshotService;
    }

    /**
     * §3 #8 — event history ascending by sequenceNumber for client replay (§13):
     * events with {@code sequenceNumber > afterSequence}, up to {@code limit} (capped
     * at 1000). {@code displayName} is resolved from drawing_contributors (every
     * persisted event upserts a contributor row).
     */
    @GetMapping("/{drawingId}/history")
    public HistoryResponse getHistory(@PathVariable UUID drawingId,
                                      @RequestParam(name = "afterSequence", defaultValue = "0") long afterSequence,
                                      @RequestParam(name = "limit", defaultValue = "500") int limit) {
        ensureDrawingExists(drawingId);
        int cappedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<DrawingEvent> events = drawingEventRepository
                .findByDrawingIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                        drawingId, afterSequence, PageRequest.of(0, cappedLimit));
        Map<UUID, String> displayNames = contributorDisplayNames(drawingId);
        List<DrawingEventResponse> items = events.stream()
                .map(event -> new DrawingEventResponse(
                        event.getEventId(),
                        event.getParticipantId(),
                        displayNames.getOrDefault(event.getParticipantId(), UNKNOWN_ARTIST),
                        event.getSequenceNumber(),
                        event.getEventType(),
                        event.getEventData(),
                        event.getCreatedAt()))
                .toList();
        long lastSequence = events.isEmpty() ? afterSequence : events.get(events.size() - 1).getSequenceNumber();
        return new HistoryResponse(drawingId, lastSequence, events.size() == cappedLimit, items);
    }

    /** §3 #9 — latest snapshot (200) or 404 when none exists yet. */
    @GetMapping("/{drawingId}/snapshot")
    public SnapshotResponse getSnapshot(@PathVariable UUID drawingId) {
        ensureDrawingExists(drawingId);
        SnapshotResponse response = snapshotService.getLatestSnapshot(drawingId);
        if (response == null) {
            throw new RoomNotFoundException("No snapshot available for drawing: " + drawingId);
        }
        return response;
    }

    private void ensureDrawingExists(UUID drawingId) {
        drawingRepository.findById(drawingId)
                .orElseThrow(() -> new RoomNotFoundException("Drawing not found: " + drawingId));
    }

    private Map<UUID, String> contributorDisplayNames(UUID drawingId) {
        return drawingContributorRepository.findByDrawingIdOrderByTotalOperationsDesc(drawingId)
                .stream()
                .collect(Collectors.toMap(
                        DrawingContributor::getParticipantId,
                        DrawingContributor::getDisplayName,
                        (first, second) -> first));
    }

    @GetMapping("/health")
    public String health()
    {
        return "OK";
    }
}

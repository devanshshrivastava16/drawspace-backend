package com.drawspace.service;

import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drawspace.dto.ContributorListResponse;
import com.drawspace.dto.ContributorStat;
import com.drawspace.entity.DrawingContributor;
import com.drawspace.repository.DrawingContributorRepository;
import com.drawspace.repository.DrawingEventRepository;

/**
 * Contributor statistics (CONTRACT §6/§11): upserts {@code drawing_contributors} on
 * every accepted event (first/last contribution timestamps, total_operations++,
 * tools_used += tool) and assembles the §3 #7 contributor list.
 */
@Service
public class ContributorService {

    private static final Logger log = LoggerFactory.getLogger(ContributorService.class);

    private final DrawingContributorRepository drawingContributorRepository;
    private final DrawingEventRepository drawingEventRepository;

    public ContributorService(DrawingContributorRepository drawingContributorRepository,
                              DrawingEventRepository drawingEventRepository) {
        this.drawingContributorRepository = drawingContributorRepository;
        this.drawingEventRepository = drawingEventRepository;
    }

    /**
     * Upserts the contributor row for a persisted event (joins the caller's transaction).
     * {@code tool} may be null (non-drawing ops such as CLEAR/UNDO).
     */
    @Transactional
    public void onContribution(UUID drawingId, UUID participantId,
                               String displayName, String assignedColor, String eventType, String tool) {
        Instant now = Instant.now();
        DrawingContributor contributor = drawingContributorRepository
                .findByDrawingIdAndParticipantId(drawingId, participantId)
                .orElse(null);
        if (contributor == null) {
            List<String> tools = new ArrayList<>();
            if (tool != null) {
                tools.add(tool);
            }
            drawingContributorRepository.save(new DrawingContributor(
                    drawingId, participantId, displayName, assignedColor, now, now, 1L, tools));
            log.debug("New contributor {} on drawing {} via {}", participantId, drawingId, eventType);
            return;
        }
        contributor.setLastContributedAt(now);
        contributor.setTotalOperations(contributor.getTotalOperations() + 1);
        if (contributor.getToolsUsed() == null) {
            contributor.setToolsUsed(new ArrayList<>());
        }
        if (tool != null && !contributor.getToolsUsed().contains(tool)) {
            contributor.getToolsUsed().add(tool);
        }
        drawingContributorRepository.save(contributor);
    }

    /** §3 #7 response: contributors sorted by totalOperations desc, drawing total event count. */
    @Transactional(readOnly = true)
    public ContributorListResponse getContributorStats(UUID drawingId) {
        long totalOperations = drawingEventRepository.countByDrawingId(drawingId);
        List<ContributorStat> contributors = drawingContributorRepository
                .findByDrawingIdOrderByTotalOperationsDesc(drawingId)
                .stream()
                .map(c -> new ContributorStat(
                        c.getParticipantId(),
                        c.getDisplayName(),
                        c.getAssignedColor(),
                        c.getFirstContributedAt(),
                        c.getLastContributedAt(),
                        c.getTotalOperations(),
                        c.getToolsUsed() == null ? List.of() : List.copyOf(c.getToolsUsed())))
                .toList();
        return new ContributorListResponse(drawingId, totalOperations, contributors);
    }
}

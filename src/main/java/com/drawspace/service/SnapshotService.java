package com.drawspace.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drawspace.dto.SnapshotResponse;
import com.drawspace.entity.CanvasSnapshot;
import com.drawspace.repository.CanvasSnapshotRepository;

/**
 * Builds and persists canonical canvas snapshots (CONTRACT §9): every
 * SNAPSHOT_INTERVAL accepted events, the current canonical state (latest snapshot +
 * replayed events) is serialized to {@code {canvasBg, layers, objects (live only),
 * lastSequence}} and stored in {@code canvas_snapshots}.
 *
 * <p>Snapshot creation is invoked from the save pipeline's AFTER-COMMIT path; a
 * failure is logged and must never fail the drawing operation itself.</p>
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final CanvasStateService canvasStateService;
    private final CanvasSnapshotRepository canvasSnapshotRepository;

    public SnapshotService(CanvasStateService canvasStateService,
                           CanvasSnapshotRepository canvasSnapshotRepository) {
        this.canvasStateService = canvasStateService;
        this.canvasSnapshotRepository = canvasSnapshotRepository;
    }

    /**
     * Builds the canonical state for the drawing and stores a snapshot at the given
     * sequence. Runs in its own transaction (called from an after-commit callback).
     */
    @Transactional
    public CanvasSnapshot createSnapshot(UUID drawingId, UUID roomId, long sequence) {
        CanvasStateService.CanvasState state = canvasStateService.buildState(drawingId);
        Map<String, Object> data = canvasStateService.toSnapshotData(state, sequence);
        CanvasSnapshot snapshot = canvasSnapshotRepository.save(
                new CanvasSnapshot(drawingId, sequence, data, null, Instant.now()));
        log.debug("Saved snapshot of drawing {} (room {}) at sequence {}", drawingId, roomId, sequence);
        return snapshot;
    }

    /** @return the latest snapshot (highest sequence) as a §3 #9 response, or {@code null}. */
    @Transactional(readOnly = true)
    public SnapshotResponse getLatestSnapshot(UUID drawingId) {
        return canvasSnapshotRepository.findTopByDrawingIdOrderBySequenceNumberDesc(drawingId)
                .map(snapshot -> new SnapshotResponse(
                        snapshot.getDrawingId(),
                        snapshot.getSequenceNumber(),
                        snapshot.getSnapshotData(),
                        snapshot.getCreatedAt()))
                .orElse(null);
    }
}

package com.drawspace.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.drawspace.entity.DrawingEvent;

/**
 * Drawing event log persistence (CONTRACT §6/§8/§11).
 * Sequence numbers are per-room (UNIQUE(room_id, sequence_number)); history is read
 * per-drawing ascending by sequence_number.
 */
public interface DrawingEventRepository extends JpaRepository<DrawingEvent, UUID> {

    /** Dedupe lookup by the client-supplied idempotency key (§6). */
    Optional<DrawingEvent> findByEventId(UUID eventId);

    /** Max sequence_number so far for a room → next = max + 1 (§6, under room row lock). */
    Optional<DrawingEvent> findTopByRoomIdOrderBySequenceNumberDesc(UUID roomId);

    /** History paging (§3 #8): events after a sequence, ascending. */
    List<DrawingEvent> findByDrawingIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            UUID drawingId, long afterSequence, Pageable pageable);

    long countByDrawingId(UUID drawingId);
}

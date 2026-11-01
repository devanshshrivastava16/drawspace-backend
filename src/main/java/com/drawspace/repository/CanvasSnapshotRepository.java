package com.drawspace.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.drawspace.entity.CanvasSnapshot;

/**
 * Canvas snapshot persistence (CONTRACT §9/§11). Latest snapshot per drawing
 * (highest sequence_number) is the replay starting point.
 */
public interface CanvasSnapshotRepository extends JpaRepository<CanvasSnapshot, UUID> {

    Optional<CanvasSnapshot> findTopByDrawingIdOrderBySequenceNumberDesc(UUID drawingId);
}

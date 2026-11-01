package com.drawspace.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.drawspace.entity.DrawingContributor;

/**
 * Contributor stats persistence (CONTRACT §6/§11): upsert on every accepted event
 * (first/last timestamps, total_operations++, tools_used += tool).
 */
public interface DrawingContributorRepository extends JpaRepository<DrawingContributor, UUID> {

    Optional<DrawingContributor> findByDrawingIdAndParticipantId(UUID drawingId, UUID participantId);

    List<DrawingContributor> findByDrawingIdOrderByTotalOperationsDesc(UUID drawingId);
}

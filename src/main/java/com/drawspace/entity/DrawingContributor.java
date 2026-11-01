package com.drawspace.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * {@code drawing_contributors} row (CONTRACT §11) — per-drawing contribution stats:
 * first/last contribution timestamps, total operation count and the set of tools used
 * (stored as JSONB array of tool names).
 */
@Entity
@Table(name = "drawing_contributors",
        uniqueConstraints = @UniqueConstraint(name = "uq_drawing_contributors_drawing_participant",
                columnNames = {"drawing_id", "participant_id"}))
public class DrawingContributor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "drawing_id", nullable = false)
    private UUID drawingId;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "display_name", nullable = false, length = 24)
    private String displayName;

    @Column(name = "assigned_color", nullable = false, length = 9)
    private String assignedColor;

    @Column(name = "first_contributed_at", nullable = false)
    private Instant firstContributedAt;

    @Column(name = "last_contributed_at", nullable = false)
    private Instant lastContributedAt;

    @Column(name = "total_operations", nullable = false)
    private long totalOperations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tools_used", nullable = false)
    private List<String> toolsUsed;

    protected DrawingContributor() {
        // JPA
    }

    public DrawingContributor(UUID drawingId, UUID participantId, String displayName, String assignedColor,
                              Instant firstContributedAt, Instant lastContributedAt,
                              long totalOperations, List<String> toolsUsed) {
        this.drawingId = drawingId;
        this.participantId = participantId;
        this.displayName = displayName;
        this.assignedColor = assignedColor;
        this.firstContributedAt = firstContributedAt;
        this.lastContributedAt = lastContributedAt;
        this.totalOperations = totalOperations;
        this.toolsUsed = toolsUsed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDrawingId() {
        return drawingId;
    }

    public void setDrawingId(UUID drawingId) {
        this.drawingId = drawingId;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public void setParticipantId(UUID participantId) {
        this.participantId = participantId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAssignedColor() {
        return assignedColor;
    }

    public void setAssignedColor(String assignedColor) {
        this.assignedColor = assignedColor;
    }

    public Instant getFirstContributedAt() {
        return firstContributedAt;
    }

    public void setFirstContributedAt(Instant firstContributedAt) {
        this.firstContributedAt = firstContributedAt;
    }

    public Instant getLastContributedAt() {
        return lastContributedAt;
    }

    public void setLastContributedAt(Instant lastContributedAt) {
        this.lastContributedAt = lastContributedAt;
    }

    public long getTotalOperations() {
        return totalOperations;
    }

    public void setTotalOperations(long totalOperations) {
        this.totalOperations = totalOperations;
    }

    public List<String> getToolsUsed() {
        return toolsUsed;
    }

    public void setToolsUsed(List<String> toolsUsed) {
        this.toolsUsed = toolsUsed;
    }
}

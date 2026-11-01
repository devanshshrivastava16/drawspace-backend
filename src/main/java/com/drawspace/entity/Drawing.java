package com.drawspace.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code drawings} row (CONTRACT §11). The canonical drawing of a room; its event log
 * lives in {@link DrawingEvent}, snapshots in {@link CanvasSnapshot}, contribution
 * stats in {@link DrawingContributor}.
 */
@Entity
@Table(name = "drawings")
public class Drawing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "created_by_participant_id", nullable = false)
    private UUID createdByParticipantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Drawing() {
        // JPA
    }

    public Drawing(String title, UUID roomId, UUID createdByParticipantId,
                   Instant createdAt, Instant updatedAt) {
        this.title = title;
        this.roomId = roomId;
        this.createdByParticipantId = createdByParticipantId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public void setRoomId(UUID roomId) {
        this.roomId = roomId;
    }

    public UUID getCreatedByParticipantId() {
        return createdByParticipantId;
    }

    public void setCreatedByParticipantId(UUID createdByParticipantId) {
        this.createdByParticipantId = createdByParticipantId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

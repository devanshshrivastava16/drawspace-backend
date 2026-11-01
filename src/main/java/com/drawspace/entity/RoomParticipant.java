package com.drawspace.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * {@code room_participants} row (CONTRACT §11). {@code left_at IS NULL} means currently
 * enrolled; combined with {@code last_active_at >= now() - staleThreshold} it defines
 * room capacity (§10). {@code participant_token} is the secret verified on every
 * privileged action (§1).
 */
@Entity
@Table(name = "room_participants",
        uniqueConstraints = @UniqueConstraint(name = "uq_room_participants_room_participant",
                columnNames = {"room_id", "participant_id"}),
        indexes = @Index(name = "idx_room_participants_room_left", columnList = "room_id, left_at"))
public class RoomParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "participant_token", nullable = false)
    private UUID participantToken;

    @Column(name = "display_name", nullable = false, length = 24)
    private String displayName;

    @Column(name = "assigned_color", nullable = false, length = 9)
    private String assignedColor;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    @Column(name = "left_at")
    private Instant leftAt;

    protected RoomParticipant() {
        // JPA
    }

    public RoomParticipant(UUID roomId, UUID participantId, UUID participantToken, String displayName,
                           String assignedColor, Instant joinedAt, Instant lastActiveAt, Instant leftAt) {
        this.roomId = roomId;
        this.participantId = participantId;
        this.participantToken = participantToken;
        this.displayName = displayName;
        this.assignedColor = assignedColor;
        this.joinedAt = joinedAt;
        this.lastActiveAt = lastActiveAt;
        this.leftAt = leftAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public void setRoomId(UUID roomId) {
        this.roomId = roomId;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public void setParticipantId(UUID participantId) {
        this.participantId = participantId;
    }

    public UUID getParticipantToken() {
        return participantToken;
    }

    public void setParticipantToken(UUID participantToken) {
        this.participantToken = participantToken;
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

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public Instant getLeftAt() {
        return leftAt;
    }

    public void setLeftAt(Instant leftAt) {
        this.leftAt = leftAt;
    }
}

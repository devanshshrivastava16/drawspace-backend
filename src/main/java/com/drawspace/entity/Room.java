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
 * {@code rooms} row (CONTRACT §11). A drawing room with a unique 6-char code,
 * a TTL-based expiry ({@code expires_at = created_at + ROOM_TTL_HOURS}) and a
 * max participant capacity. {@code owner_participant_id} transfers to the
 * earliest-joined remaining active participant when the owner leaves (§10).
 */
@Entity
@Table(name = "rooms")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "room_code", nullable = false, unique = true, length = 6)
    private String roomCode;

    @Column(name = "title", length = 60)
    private String title;

    @Column(name = "owner_participant_id", nullable = false)
    private UUID ownerParticipantId;

    @Column(name = "max_participants", nullable = false)
    private int maxParticipants;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected Room() {
        // JPA
    }

    public Room(String roomCode, String title, UUID ownerParticipantId, int maxParticipants,
                Instant createdAt, Instant expiresAt, boolean active) {
        this.roomCode = roomCode;
        this.title = title;
        this.ownerParticipantId = ownerParticipantId;
        this.maxParticipants = maxParticipants;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public UUID getOwnerParticipantId() {
        return ownerParticipantId;
    }

    public void setOwnerParticipantId(UUID ownerParticipantId) {
        this.ownerParticipantId = ownerParticipantId;
    }

    public int getMaxParticipants() {
        return maxParticipants;
    }

    public void setMaxParticipants(int maxParticipants) {
        this.maxParticipants = maxParticipants;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}

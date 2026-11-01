package com.drawspace.entity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * {@code drawing_events} row (CONTRACT §11) — the append-only event log.
 * {@code event_id} is the client-supplied idempotency key (§6 dedupe);
 * {@code sequence_number} is per-room monotonic (UNIQUE(room_id, sequence_number));
 * {@code event_data} is the full op payload as JSONB (§7).
 */
@Entity
@Table(name = "drawing_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_drawing_events_event_id", columnNames = "event_id"),
                @UniqueConstraint(name = "uq_drawing_events_room_sequence", columnNames = {"room_id", "sequence_number"})
        },
        indexes = @Index(name = "idx_drawing_events_drawing_sequence", columnList = "drawing_id, sequence_number"))
public class DrawingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "drawing_id", nullable = false)
    private UUID drawingId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_data", nullable = false)
    private Map<String, Object> eventData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DrawingEvent() {
        // JPA
    }

    public DrawingEvent(UUID eventId, UUID drawingId, UUID roomId, UUID participantId,
                        long sequenceNumber, String eventType, Map<String, Object> eventData,
                        Instant createdAt) {
        this.eventId = eventId;
        this.drawingId = drawingId;
        this.roomId = roomId;
        this.participantId = participantId;
        this.sequenceNumber = sequenceNumber;
        this.eventType = eventType;
        this.eventData = eventData;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public UUID getDrawingId() {
        return drawingId;
    }

    public void setDrawingId(UUID drawingId) {
        this.drawingId = drawingId;
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

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Map<String, Object> getEventData() {
        return eventData;
    }

    public void setEventData(Map<String, Object> eventData) {
        this.eventData = eventData;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

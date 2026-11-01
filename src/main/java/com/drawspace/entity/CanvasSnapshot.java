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

/**
 * {@code canvas_snapshots} row (CONTRACT §11) — periodic canonical-state snapshots
 * (§9: {@code snapshot_data} = {canvasBg, layers, objects (live only), lastSequence}),
 * persisted every SNAPSHOT_INTERVAL accepted events.
 */
@Entity
@Table(name = "canvas_snapshots",
        indexes = @Index(name = "idx_canvas_snapshots_drawing_sequence", columnList = "drawing_id, sequence_number"))
public class CanvasSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "drawing_id", nullable = false)
    private UUID drawingId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_data", nullable = false)
    private Map<String, Object> snapshotData;

    @Column(name = "preview_image_reference", length = 512)
    private String previewImageReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CanvasSnapshot() {
        // JPA
    }

    public CanvasSnapshot(UUID drawingId, long sequenceNumber, Map<String, Object> snapshotData,
                          String previewImageReference, Instant createdAt) {
        this.drawingId = drawingId;
        this.sequenceNumber = sequenceNumber;
        this.snapshotData = snapshotData;
        this.previewImageReference = previewImageReference;
        this.createdAt = createdAt;
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

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public Map<String, Object> getSnapshotData() {
        return snapshotData;
    }

    public void setSnapshotData(Map<String, Object> snapshotData) {
        this.snapshotData = snapshotData;
    }

    public String getPreviewImageReference() {
        return previewImageReference;
    }

    public void setPreviewImageReference(String previewImageReference) {
        this.previewImageReference = previewImageReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

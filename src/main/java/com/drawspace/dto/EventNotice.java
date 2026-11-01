package com.drawspace.dto;

import java.time.Instant;

/**
 * Lifecycle notice broadcast on /topic/room/{code}/events (CONTRACT §4):
 * type = PARTICIPANT_JOINED | PARTICIPANT_LEFT | OWNER_CHANGED | SNAPSHOT_SAVED.
 */
public record EventNotice(String type, String participantId, String displayName, String message, Instant timestamp) {
}

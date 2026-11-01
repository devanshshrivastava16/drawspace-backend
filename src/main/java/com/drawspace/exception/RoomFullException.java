package com.drawspace.exception;

/**
 * 409 — room at capacity (CONTRACT §10: capacity = participants with left_at IS NULL
 * AND last_active_at >= stale threshold). May carry the room's maxParticipants.
 */
public class RoomFullException extends RuntimeException {

    private final Integer maxParticipants;

    public RoomFullException(String message) {
        this(message, null);
    }

    public RoomFullException(String message, Integer maxParticipants) {
        super(message);
        this.maxParticipants = maxParticipants;
    }

    public Integer getMaxParticipants() {
        return maxParticipants;
    }
}

package com.drawspace.exception;

/** 410 — room expired (past expires_at) or deactivated by the scheduler (CONTRACT §10). */
public class RoomInactiveException extends RuntimeException {

    public RoomInactiveException(String message) {
        super(message);
    }
}

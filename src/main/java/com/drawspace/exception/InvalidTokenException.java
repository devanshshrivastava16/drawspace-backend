package com.drawspace.exception;

/** 401 — participantToken invalid for the room/participant (CONTRACT §1). */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}

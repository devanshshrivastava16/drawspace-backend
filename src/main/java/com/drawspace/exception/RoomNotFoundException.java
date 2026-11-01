package com.drawspace.exception;

/** 404 — room code unknown. */
public class RoomNotFoundException extends RuntimeException {

    public RoomNotFoundException(String message) {
        super(message);
    }
}

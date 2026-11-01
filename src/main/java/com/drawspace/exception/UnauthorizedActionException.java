package com.drawspace.exception;

/** 403 — e.g. non-owner attempting an owner-only action (CONTRACT §3 #10). */
public class UnauthorizedActionException extends RuntimeException {

    public UnauthorizedActionException(String message) {
        super(message);
    }
}

package com.drawspace.exception;

/** 400 — operation rejected: unknown enum, type/tool mismatch, range violation, bad payload (CONTRACT §6/§7). */
public class InvalidOperationException extends RuntimeException {

    public InvalidOperationException(String message) {
        super(message);
    }
}

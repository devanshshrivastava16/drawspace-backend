package com.drawspace.util;

import java.security.SecureRandom;

/**
 * Generates unique 6-char room codes from the alphabet mandated by CONTRACT §10:
 * {@code "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"} (no I, O, 0, 1 — unambiguous codes).
 * Secure-random to avoid guessable codes; uniqueness is enforced by the service layer
 * (retry on conflict) backed by the UNIQUE(room_code) constraint.
 */
public final class RoomCodes {

    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    private RoomCodes() {
    }

    /** @return a cryptographically random 6-char room code. */
    public static String generate() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}

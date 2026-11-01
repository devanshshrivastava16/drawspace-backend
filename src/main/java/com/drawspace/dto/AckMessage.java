package com.drawspace.dto;

/**
 * Ack sent to the originating participant on /user/queue/ack via
 * convertAndSendToUser(participantId, "/queue/ack", payload) (CONTRACT §4):
 * {@code status:"SAVED"} with the assigned sequence number.
 */
public record AckMessage(String eventId, Long sequenceNumber, String status) {
}

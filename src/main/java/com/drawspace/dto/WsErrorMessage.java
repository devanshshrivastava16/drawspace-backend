package com.drawspace.dto;

/**
 * Error sent to a participant on /user/queue/errors via
 * @MessageExceptionHandler + @SendToUser("/queue/errors") (CONTRACT §4).
 */
public record WsErrorMessage(String eventId, String message) {
}

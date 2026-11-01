package com.drawspace.websocket;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WebSocketSessionContextRegistry {

    private final ConcurrentMap<String, WebSocketSessionContext> sessions =
            new ConcurrentHashMap<>();

    public void register(String sessionId, WebSocketSessionContext context) {
        if (sessionId == null || sessionId.isBlank() || context == null) {
            throw new IllegalArgumentException("Session ID and context are required");
        }

        sessions.put(sessionId, context);
    }

    public Optional<WebSocketSessionContext> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(sessions.get(sessionId));
    }

    public WebSocketSessionContext remove(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        return sessions.remove(sessionId);
    }

    public int size() {
        return sessions.size();
    }
}
package com.drawspace.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-specific configuration bound from the {@code drawspace.*} namespace
 * (CONTRACT §12; see application.yml for the env placeholders and defaults).
 *
 * @param roomTtlHours           rooms.expires_at = created_at + roomTtlHours (ROOM_TTL_HOURS)
 * @param snapshotInterval       snapshot persisted every N accepted drawing events (SNAPSHOT_INTERVAL)
 * @param staleThresholdMinutes  participants without heartbeat for this long are stale (STALE_THRESHOLD_MINUTES)
 * @param maxParticipants        capacity per room (MAX_PARTICIPANTS)
 * @param cors                   CORS settings (CORS_ALLOWED_ORIGINS)
 */
@ConfigurationProperties(prefix = "drawspace")
public record DrawSpaceProperties(
        int roomTtlHours,
        int snapshotInterval,
        int staleThresholdMinutes,
        int maxParticipants,
        Cors cors) {

    /**
     * Comma-separated allowed origins,
     * e.g. {@code "http://localhost:5173,https://drawspace.example.com"}.
     */
    public record Cors(String allowedOrigins) {

        /** Splits {@code allowedOrigins} on commas (tolerating surrounding whitespace). */
        public List<String> allowedOriginList() {
            if (allowedOrigins == null || allowedOrigins.isBlank()) {
                return List.of();
            }
            return List.of(allowedOrigins.split("\\s*,\\s*"));
        }
    }
}

package com.drawspace.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contributor stat row (CONTRACT §3 #7) — from drawing_contributors upserts (§6).
 */
public record ContributorStat(
        UUID participantId,
        String displayName,
        String color,
        Instant firstContributedAt,
        Instant lastContributedAt,
        long totalOperations,
        List<String> toolsUsed) {
}

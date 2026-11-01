package com.drawspace.dto;

import java.util.List;
import java.util.UUID;

/**
 * GET /api/drawings/{drawingId}/contributors response (CONTRACT §3 #7).
 */
public record ContributorListResponse(
        UUID drawingId,
        long totalOperations,
        List<ContributorStat> contributors) {
}

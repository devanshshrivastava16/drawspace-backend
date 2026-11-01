package com.drawspace.dto;

import java.util.List;
import java.util.UUID;

/**
 * GET /api/drawings/{drawingId}/history response (CONTRACT §3 #8): events ascending;
 * client pages with afterSequence until hasMore=false (§13).
 */
public record HistoryResponse(
        UUID drawingId,
        long lastSequence,
        boolean hasMore,
        List<DrawingEventResponse> events) {
}

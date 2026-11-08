package com.bazaar.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CampaignResponse(
        UUID id,
        String sellerId,
        UUID productId,
        BigDecimal price,
        String currency,
        String status,
        int quota,
        int remaining,
        int perCustomerLimit,
        Instant startsAt,
        Instant endsAt) {
}

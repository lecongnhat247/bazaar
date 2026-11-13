package com.bazaar.catalog.api;

import java.time.Instant;
import java.util.UUID;

import com.bazaar.catalog.product.ProductModeration;

public record ModerationResponse(
        UUID productId,
        String actorSubject,
        String action,
        String reason,
        String resultStatus,
        Instant createdAt) {

    public static ModerationResponse from(ProductModeration row) {
        return new ModerationResponse(
                row.getProductId(),
                row.getActorSubject(),
                row.getAction(),
                row.getReason(),
                row.getResultStatus(),
                row.getCreatedAt());
    }
}

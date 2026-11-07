package com.bazaar.inventory.stock;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID id,
        UUID productId,
        int quantity,
        String status,
        String requesterId,
        String requestHash,
        Instant expiresAt,
        Instant createdAt) {

    public ReservationResponse withoutSecrets() {
        return new ReservationResponse(id, productId, quantity, status, null, null, expiresAt, createdAt);
    }
}

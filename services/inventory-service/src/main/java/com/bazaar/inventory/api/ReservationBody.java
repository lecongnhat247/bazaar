package com.bazaar.inventory.api;

import java.time.Instant;
import java.util.UUID;

import com.bazaar.inventory.stock.ReservationResponse;

public record ReservationBody(
        UUID id,
        UUID productId,
        int quantity,
        String status,
        Instant expiresAt,
        Instant createdAt) {

    public static ReservationBody from(ReservationResponse response) {
        return new ReservationBody(
                response.id(),
                response.productId(),
                response.quantity(),
                response.status(),
                response.expiresAt(),
                response.createdAt());
    }
}

package com.bazaar.order.market;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FulfillmentView(
        UUID id,
        String sellerId,
        String status,
        String shipmentReference,
        Instant packedAt,
        Instant shippedAt,
        Instant deliveredAt,
        Instant cancelledAt,
        Instant updatedAt,
        List<Event> history) {

    public record Event(String from, String to, String actor, String note, Instant at) {
    }
}

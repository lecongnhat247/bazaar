package com.bazaar.payment.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessage(
        UUID id,
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        int eventVersion,
        UUID correlationId,
        UUID causationId,
        String payload,
        Instant occurredAt,
        int retryCount,
        String traceparent) {
}

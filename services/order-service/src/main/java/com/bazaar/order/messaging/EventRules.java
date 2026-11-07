package com.bazaar.order.messaging;

final class EventRules {

    private EventRules() {
    }

    static void require(EventEnvelope event) {
        if (event == null || event.eventId() == null || blank(event.eventType())) {
            throw new IllegalArgumentException("Event requires eventId and eventType.");
        }
        if (event.eventVersion() < 1) {
            throw new IllegalArgumentException("Event version starts at 1.");
        }
        if (blank(event.aggregateId()) || blank(event.aggregateType()) || event.occurredAt() == null) {
            throw new IllegalArgumentException("Event requires an aggregate and occurredAt.");
        }
        if (event.correlationId() == null || blank(event.producer())) {
            throw new IllegalArgumentException("Event requires correlationId and producer.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

package com.bazaar.catalog.messaging;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateId,
        String aggregateType,
        Long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        UUID causationId,
        String producer,
        JsonNode payload) {
}

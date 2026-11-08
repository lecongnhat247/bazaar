package com.bazaar.order.outbox;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bazaar.order.messaging.EventEnvelope;
import com.bazaar.order.messaging.EventPublisher;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxStore store;
    private final EventPublisher events;
    private final ObjectMapper objectMapper;
    private final String producer;
    private final int batchSize;
    private final Duration stale;
    private final int maxAttempts;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(
            OutboxStore store,
            EventPublisher events,
            ObjectMapper objectMapper,
            MeterRegistry meters,
            @Value("${spring.application.name}") String producer,
            @Value("${bazaar.outbox.batch-size:20}") int batchSize,
            @Value("${bazaar.outbox.stale:30s}") Duration stale,
            @Value("${bazaar.outbox.max-attempts:5}") int maxAttempts) {
        this.store = store;
        this.events = events;
        this.objectMapper = objectMapper;
        this.producer = producer;
        this.batchSize = batchSize;
        this.stale = stale;
        this.maxAttempts = maxAttempts;
        this.published = meters.counter("bazaar.outbox.published");
        this.failed = meters.counter("bazaar.outbox.failed");
        meters.gauge("bazaar.outbox.pending", store, OutboxStore::pending);
        meters.gauge("bazaar.outbox.dead", store, OutboxStore::dead);
    }

    @Scheduled(fixedDelayString = "${bazaar.outbox.poll-delay:1000}", initialDelayString = "${bazaar.outbox.poll-delay:1000}")
    public void publishPending() {
        Instant now = Instant.now();
        int interrupted = store.recoverStale(now.minus(stale), maxAttempts, now.plusSeconds(2));
        if (interrupted > 0) {
            log.warn("Outbox recovered interrupted publications count={}", interrupted);
        }
        for (OutboxMessage message : store.claim(batchSize, now)) {
            try {
                events.publish(message.topic(), envelope(message));
                store.markPublished(message.id(), Instant.now());
                published.increment();
            } catch (RuntimeException exception) {
                int attempt = message.retryCount() + 1;
                String status = store.markFailed(message.id(), exception.toString(), maxAttempts, nextAttempt(now, attempt));
                failed.increment();
                if ("DEAD".equals(status)) {
                    log.error(
                            "Outbox publication exhausted eventId={} correlationId={} aggregateId={} retryCount={} error={}",
                            message.eventId(),
                            message.correlationId(),
                            message.aggregateId(),
                            attempt,
                            exception.toString());
                } else {
                    log.warn(
                            "Outbox publication failed eventId={} correlationId={} aggregateId={} retryCount={}",
                            message.eventId(),
                            message.correlationId(),
                            message.aggregateId(),
                            attempt);
                }
            }
        }
    }

    private static Instant nextAttempt(Instant now, int attempt) {
        long seconds = Math.min(30L, 1L << Math.min(Math.max(attempt - 1, 0), 4));
        return now.plusSeconds(seconds);
    }

    private EventEnvelope envelope(OutboxMessage message) {
        return new EventEnvelope(
                message.eventId(),
                message.eventType(),
                message.eventVersion(),
                message.aggregateId(),
                message.aggregateType(),
                null,
                message.occurredAt(),
                message.correlationId(),
                message.causationId(),
                producer,
                objectMapper.readTree(message.payload()));
    }
}

package com.bazaar.order.messaging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bazaar.order.outbox.ProcessedEvents;

import tools.jackson.databind.ObjectMapper;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final ProcessedEvents processed;
    private final List<Consumer<EventEnvelope>> watchers = new CopyOnWriteArrayList<>();

    public OrderEventConsumer(ObjectMapper objectMapper, ProcessedEvents processed) {
        this.objectMapper = objectMapper;
        this.processed = processed;
    }

    public Registration watch(Consumer<EventEnvelope> watcher) {
        watchers.add(watcher);
        return () -> watchers.remove(watcher);
    }

    @KafkaListener(topics = "${bazaar.order.events-topic}", groupId = "order-service")
    @Transactional
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = objectMapper.readValue(record.value(), EventEnvelope.class);
        EventRules.require(event);
        if (!event.aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("Kafka key must be the aggregate id.");
        }
        if (!processed.record("order-service", event)) {
            log.info("Duplicate event ignored eventId={}", event.eventId());
            return;
        }
        log.info(
                "Consumed event eventId={} eventType={} aggregateId={} correlationId={}",
                event.eventId(),
                event.eventType(),
                event.aggregateId(),
                event.correlationId());
        for (Consumer<EventEnvelope> watcher : watchers) {
            watcher.accept(event);
        }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}

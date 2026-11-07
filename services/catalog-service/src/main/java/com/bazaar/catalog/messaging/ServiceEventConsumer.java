package com.bazaar.catalog.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bazaar.catalog.outbox.ProcessedEvents;

import tools.jackson.databind.ObjectMapper;

@Component
public class ServiceEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ServiceEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final ProcessedEvents processed;

    public ServiceEventConsumer(ObjectMapper objectMapper, ProcessedEvents processed) {
        this.objectMapper = objectMapper;
        this.processed = processed;
    }

    @KafkaListener(topics = "${bazaar.catalog.events-topic}", groupId = "catalog-service")
    @Transactional
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = objectMapper.readValue(record.value(), EventEnvelope.class);
        EventRules.require(event);
        if (!event.aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("Kafka key must be the aggregate id.");
        }
        if (!processed.record("catalog-service", event)) {
            log.info("Duplicate event ignored eventId={}", event.eventId());
            return;
        }
        log.info(
                "Consumed event eventId={} eventType={} aggregateId={} correlationId={}",
                event.eventId(),
                event.eventType(),
                event.aggregateId(),
                event.correlationId());
    }
}

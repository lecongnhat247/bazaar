package com.bazaar.order.checkout;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.bazaar.order.messaging.EventEnvelope;
import com.bazaar.order.messaging.EventRules;

import tools.jackson.databind.ObjectMapper;

@Component
public class SagaListener {

    private final ObjectMapper objectMapper;
    private final SagaCoordinator saga;

    public SagaListener(ObjectMapper objectMapper, SagaCoordinator saga) {
        this.objectMapper = objectMapper;
        this.saga = saga;
    }

    @KafkaListener(topics = "${bazaar.order.inventory-events-topic}", groupId = "order-saga")
    public void onInventory(ConsumerRecord<String, String> record) {
        saga.accept(read(record));
    }

    @KafkaListener(topics = "${bazaar.order.payment-events-topic}", groupId = "order-saga")
    public void onPayment(ConsumerRecord<String, String> record) {
        saga.accept(read(record));
    }

    private EventEnvelope read(ConsumerRecord<String, String> record) {
        EventEnvelope event = objectMapper.readValue(record.value(), EventEnvelope.class);
        EventRules.require(event);
        if (!event.aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("Kafka key must be the aggregate id.");
        }
        return event;
    }
}

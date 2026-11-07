package com.bazaar.order.messaging;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String orderTopic;

    public EventPublisher(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            @Value("${bazaar.order.events-topic}") String orderTopic) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.orderTopic = orderTopic;
    }

    public void publishOrderEvent(EventEnvelope event) {
        publish(orderTopic, event);
    }

    public void publish(String topic, EventEnvelope event) {
        EventRules.require(event);
        String json = objectMapper.writeValueAsString(event);
        try {
            kafka.send(topic, event.aggregateId(), json).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + event.eventId(), exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka did not acknowledge " + event.eventId(), exception);
        }
    }
}

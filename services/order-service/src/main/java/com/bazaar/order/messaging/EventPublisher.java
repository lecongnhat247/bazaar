package com.bazaar.order.messaging;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.bazaar.order.outbox.TraceContext;

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
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.aggregateId(), json);
        if (event.correlationId() != null) {
            record.headers().add("correlationId", event.correlationId().toString().getBytes(StandardCharsets.UTF_8));
        }
        String traceparent = TraceContext.current();
        if (traceparent != null) {
            record.headers().remove("traceparent");
            record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        }
        try {
            kafka.send(record).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + event.eventId(), exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka did not acknowledge " + event.eventId(), exception);
        }
    }
}

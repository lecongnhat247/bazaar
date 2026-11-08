package com.bazaar.inventory.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import com.bazaar.inventory.outbox.TraceContext;

/**
 * Writes the current W3C traceparent after Kafka's own observation.
 * The observation starts a new trace when it cannot see the outbox span.
 */
public class TraceProducerInterceptor implements ProducerInterceptor<String, String> {

    @Override
    public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
        String traceparent = TraceContext.current();
        if (traceparent != null) {
            record.headers().remove("traceparent");
            record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}

package com.bazaar.catalog.config;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import com.bazaar.catalog.outbox.TraceContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

@Component
public class KafkaRecordContext implements RecordInterceptor<Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(KafkaRecordContext.class);
    private final Counter processed;
    private final ThreadLocal<TraceContext> opened = new ThreadLocal<>();

    public KafkaRecordContext(MeterRegistry meters) {
        this.processed = meters.counter("bazaar.kafka.processed");
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        put("eventId", field(record.value(), "eventId"));
        put("correlationId", field(record.value(), "correlationId"));
        opened.set(TraceContext.open(firstHeader(record, "traceparent")));
        copySpan();
        return record;
    }

    @Override
    public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        processed.increment();
        copySpan();
        log.info("Kafka record consumed topic={}", record.topic());
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        TraceContext scope = opened.get();
        opened.remove();
        if (scope != null) {
            scope.close();
        }
        MDC.remove("eventId");
        MDC.remove("correlationId");
        MDC.remove("traceId");
        MDC.remove("spanId");
    }

    private static void copySpan() {
        SpanContext span = Span.current().getSpanContext();
        if (span.isValid()) {
            MDC.put("traceId", span.getTraceId());
            MDC.put("spanId", span.getSpanId());
        }
    }

    private static void put(String key, String value) {
        if (!value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private static String field(Object value, String name) {
        if (value == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(value.toString());
        String found = matcher.find() ? matcher.group(1) : "";
        return found.matches("[A-Za-z0-9._:-]{8,80}") ? found : "";
    }

    private static String firstHeader(ConsumerRecord<?, ?> record, String name) {
        for (Header header : record.headers().headers(name)) {
            if (header.value() != null) {
                return new String(header.value(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}

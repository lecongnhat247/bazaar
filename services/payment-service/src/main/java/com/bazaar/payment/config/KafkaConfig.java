package com.bazaar.payment.config;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableScheduling
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            MeterRegistry meters,
            @Value("${bazaar.dlq.topic:bazaar.dlq}") String deadLetterTopic) {
        Counter retries = meters.counter("bazaar.kafka.retries");
        Counter deadLetters = meters.counter("bazaar.kafka.dlq");
        ConsumerRecordRecoverer recoverer = (ConsumerRecord<?, ?> record, Exception exception) -> {
            String eventId = field(record.value(), "eventId");
            String correlationId = field(record.value(), "correlationId");
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "originalTopic", String.valueOf(record.topic()),
                    "originalPartition", record.partition(),
                    "originalOffset", record.offset(),
                    "eventId", eventId,
                    "correlationId", correlationId,
                    "error", exception.toString(),
                    "value", String.valueOf(record.value())));
            try {
                kafka.send(deadLetterTopic, record.key() == null ? null : record.key().toString(), body).get(5, TimeUnit.SECONDS);
                deadLetters.increment();
                log.error(
                        "Dead letter originalTopic={} eventId={} correlationId={} error={}",
                        record.topic(),
                        eventId,
                        correlationId,
                        exception.toString());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.error(
                        "Dead letter was not copied originalTopic={} eventId={} correlationId={} error={}",
                        record.topic(),
                        eventId,
                        correlationId,
                        interrupted.toString());
            } catch (Exception publishError) {
                log.error(
                        "Dead letter was not copied originalTopic={} eventId={} correlationId={} error={}",
                        record.topic(),
                        eventId,
                        correlationId,
                        publishError.toString());
            }
        };
        ExponentialBackOff backOff = new ExponentialBackOff(200L, 2.0);
        backOff.setMaxInterval(2_000L);
        backOff.setMaxElapsedTime(5_000L);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class, DataIntegrityViolationException.class);
        handler.setRetryListeners((record, exception, attempt) -> {
            retries.increment();
            log.warn(
                    "Event retry originalTopic={} attempt={} error={}",
                    record.topic(),
                    attempt,
                    exception.toString());
        });
        return handler;
    }

    private static String field(Object value, String name) {
        if (value == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(value.toString());
        return matcher.find() ? matcher.group(1) : "";
    }

    @Bean
    HealthIndicator kafkaBroker(KafkaAdmin admin) {
        return () -> {
            Map<String, Object> config = new HashMap<>(admin.getConfigurationProperties());
            config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
            config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 1000);
            config.put(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG, 1000);
            try (AdminClient client = AdminClient.create(config)) {
                client.describeCluster().nodes().get(2, TimeUnit.SECONDS);
                return Health.up().build();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.warn("Kafka health check interrupted");
                return Health.down().build();
            } catch (Exception exception) {
                log.warn("Kafka health check failed: {}", exception.toString());
                return Health.down().build();
            }
        };
    }
}

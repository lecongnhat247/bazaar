package com.bazaar.payment.config;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableScheduling
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ConsumerRecordRecoverer recoverer = (ConsumerRecord<?, ?> record, Exception exception) -> log.error(
                "Event skipped after retries topic={} partition={} offset={}",
                record.topic(),
                record.partition(),
                record.offset(),
                exception);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L));
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

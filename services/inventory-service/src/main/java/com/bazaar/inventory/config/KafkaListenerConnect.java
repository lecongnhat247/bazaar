package com.bazaar.inventory.config;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class KafkaListenerConnect {

    private static final Logger log = LoggerFactory.getLogger(KafkaListenerConnect.class);

    private final KafkaListenerEndpointRegistry registry;
    private final AtomicLong lag = new AtomicLong();

    public KafkaListenerConnect(KafkaListenerEndpointRegistry registry, MeterRegistry meters) {
        this.registry = registry;
        meters.gauge("bazaar.kafka.consumer.lag", lag);
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 1_000)
    void connect() {
        boolean connected = registry.getListenerContainers().stream().anyMatch(MessageListenerContainer::isChildRunning);
        if (connected) {
            return;
        }
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (container.isRunning()) {
                container.stop();
            }
        }
        try {
            registry.start();
            log.info("Kafka listener started");
        } catch (RuntimeException exception) {
            log.warn("Kafka listener is waiting for the broker: {}", exception.toString());
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    void sampleLag() {
        long max = 0;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            for (java.util.Map<MetricName, ? extends Metric> metrics : container.metrics().values()) {
                for (Metric metric : metrics.values()) {
                    if ("records-lag-max".equals(metric.metricName().name()) && metric.metricValue() instanceof Number number) {
                        max = Math.max(max, number.longValue());
                    }
                }
            }
        }
        lag.set(max);
        log.info("Kafka consumer lag max={}", max);
    }
}

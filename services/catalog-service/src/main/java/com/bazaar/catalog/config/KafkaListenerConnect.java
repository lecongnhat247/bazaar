package com.bazaar.catalog.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KafkaListenerConnect {

    private static final Logger log = LoggerFactory.getLogger(KafkaListenerConnect.class);

    private final KafkaListenerEndpointRegistry registry;

    public KafkaListenerConnect(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
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
}

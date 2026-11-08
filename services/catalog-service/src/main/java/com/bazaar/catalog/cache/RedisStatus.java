package com.bazaar.catalog.cache;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class RedisStatus {

    private final StringRedisTemplate redis;
    private final AtomicInteger up = new AtomicInteger();

    public RedisStatus(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        meters.gauge("bazaar.redis.up", up);
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    void ping() {
        RedisConnectionFactory factory = redis.getConnectionFactory();
        if (factory == null) {
            up.set(0);
            return;
        }
        try (RedisConnection connection = factory.getConnection()) {
            String reply = connection.ping();
            up.set(reply != null && "PONG".equalsIgnoreCase(reply) ? 1 : 0);
        } catch (RuntimeException exception) {
            up.set(0);
        }
    }
}

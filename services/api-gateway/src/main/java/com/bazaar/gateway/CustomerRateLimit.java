package com.bazaar.gateway;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
public class CustomerRateLimit {

    private static final Logger log = LoggerFactory.getLogger(CustomerRateLimit.class);

    private final ReactiveStringRedisTemplate redis;
    private final JwtDecoder decoder;
    private final int readLimit;
    private final int writeLimit;
    private final Duration window;

    public CustomerRateLimit(
            ReactiveStringRedisTemplate redis,
            JwtDecoder decoder,
            @Value("${bazaar.gateway.rate-limit.read-limit:120}") int readLimit,
            @Value("${bazaar.gateway.rate-limit.write-limit:30}") int writeLimit,
            @Value("${bazaar.gateway.rate-limit.window:60s}") Duration window) {
        this.redis = redis;
        this.decoder = decoder;
        this.readLimit = readLimit;
        this.writeLimit = writeLimit;
        this.window = window;
    }

    public Mono<Boolean> permit(String method, String path, String authorization, String remoteAddress) {
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        int limit = read ? readLimit : writeLimit;
        long windowSeconds = Math.max(window.toSeconds(), 1);
        long windowId = Instant.now().getEpochSecond() / windowSeconds;
        String key = "bazaar:rl:v1:" + group(path) + ":" + (read ? "read" : "write") + ":"
                + RateLimitIdentity.tokenHash(decoder, authorization, remoteAddress) + ":" + windowId;
        return redis.opsForValue().increment(key)
                .flatMap(count -> (count == 1L ? redis.expire(key, window) : Mono.just(Boolean.TRUE)).thenReturn(count <= limit))
                .timeout(Duration.ofMillis(300))
                .onErrorResume(error -> {
                    log.warn("Rate limit allowed the request because Redis did not answer: {}", error.toString());
                    return Mono.just(Boolean.TRUE);
                });
    }

    private static String group(String path) {
        if (path.startsWith("/api/v1/products")
                || path.startsWith("/api/v1/categories")
                || path.startsWith("/api/v1/catalog")) {
            return "catalog";
        }
        if (path.startsWith("/api/v1/orders")) {
            return "orders";
        }
        if (path.startsWith("/api/v1/payments")) {
            return "payments";
        }
        if (path.startsWith("/api/v1/inventory")) {
            return "inventory";
        }
        if (path.startsWith("/api/v1/identity")) {
            return "identity";
        }
        return "other";
    }
}

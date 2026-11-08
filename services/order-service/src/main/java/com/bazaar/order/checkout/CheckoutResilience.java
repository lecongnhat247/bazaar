package com.bazaar.order.checkout;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class CheckoutResilience {

    private final CircuitBreakerRegistry circuitBreakers;
    private final CircuitBreaker catalog;
    private final CircuitBreaker inventory;
    private final CircuitBreaker payment;
    private final Retry reads;
    private final Bulkhead bulkhead;
    private final RateLimiter rateLimiter;

    public CheckoutResilience(
            MeterRegistry meters,
            @Value("${bazaar.order.resilience.retry-attempts:2}") int retryAttempts,
            @Value("${bazaar.order.resilience.retry-wait:20ms}") Duration retryWait,
            @Value("${bazaar.order.resilience.bulkhead-concurrent:8}") int bulkheadConcurrent,
            @Value("${bazaar.order.resilience.rate-limit-per-second:30}") int rateLimitPerSecond,
            @Value("${bazaar.order.resilience.circuit-window:10}") int circuitWindow,
            @Value("${bazaar.order.resilience.circuit-minimum:5}") int circuitMinimum,
            @Value("${bazaar.order.resilience.circuit-failure-rate:50}") float circuitFailureRate,
            @Value("${bazaar.order.resilience.circuit-open:10s}") Duration circuitOpen,
            @Value("${bazaar.order.resilience.read-timeout:800ms}") Duration readTimeout) {
        CircuitBreakerConfig circuitConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(circuitWindow)
                .minimumNumberOfCalls(circuitMinimum)
                .failureRateThreshold(circuitFailureRate)
                .slowCallDurationThreshold(readTimeout)
                .slowCallRateThreshold(100)
                .waitDurationInOpenState(circuitOpen)
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(CheckoutResilience::transientFailure)
                .build();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(retryAttempts)
                .intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialRandomBackoff(
                        retryWait, 2.0, 0.5))
                .retryOnException(CheckoutResilience::transientFailure)
                .build();
        this.circuitBreakers = CircuitBreakerRegistry.of(circuitConfig);
        this.catalog = circuitBreakers.circuitBreaker("catalog");
        this.inventory = circuitBreakers.circuitBreaker("inventory");
        this.payment = circuitBreakers.circuitBreaker("payment");
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);
        this.reads = retryRegistry.retry("checkout-read");
        BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(bulkheadConcurrent)
                .maxWaitDuration(Duration.ZERO)
                .build());
        this.bulkhead = bulkheadRegistry.bulkhead("checkout-downstream");
        RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(rateLimitPerSecond)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build());
        this.rateLimiter = rateLimiterRegistry.rateLimiter("checkout-downstream");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(meters);
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meters);
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry).bindTo(meters);
        TaggedRateLimiterMetrics.ofRateLimiterRegistry(rateLimiterRegistry).bindTo(meters);
    }

    public <T> T protectRead(String dependency, Supplier<T> call) {
        return finish(Retry.decorateSupplier(reads, guard(dependency, call)));
    }

    public <T> T protectWrite(String dependency, Supplier<T> call) {
        return finish(guard(dependency, call));
    }

    public void reset() {
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private <T> Supplier<T> guard(String dependency, Supplier<T> call) {
        CircuitBreaker breaker = switch (dependency) {
            case "inventory" -> inventory;
            case "payment" -> payment;
            default -> catalog;
        };
        return CircuitBreaker.decorateSupplier(breaker,
                RateLimiter.decorateSupplier(rateLimiter, Bulkhead.decorateSupplier(bulkhead, call)));
    }

    private static <T> T finish(Supplier<T> call) {
        try {
            return call.get();
        } catch (CallNotPermittedException | BulkheadFullException | RequestNotPermitted exception) {
            throw new DownstreamFailure(503);
        }
    }

    static boolean transientFailure(Throwable exception) {
        return exception instanceof DownstreamFailure failure
                && (failure.status() == 408 || failure.status() == 502 || failure.status() == 503 || failure.status() == 504);
    }
}

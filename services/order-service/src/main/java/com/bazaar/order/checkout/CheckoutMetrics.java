package com.bazaar.order.checkout;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class CheckoutMetrics {

    private static final String ACTIVE = """
            SELECT COUNT(*) FROM customer_order
            WHERE status IN ('PENDING_INVENTORY', 'PENDING_PAYMENT', 'PENDING_INVENTORY_COMMIT', 'COMPENSATING')
            """;

    private final Counter attempts;
    private final Counter accepted;
    private final Counter replayed;
    private final Counter rejected;
    private final Counter confirmed;
    private final Counter paymentFailed;
    private final Counter cancelled;
    private final Counter failed;

    public CheckoutMetrics(MeterRegistry meters, JdbcTemplate jdbc) {
        this.attempts = meters.counter("bazaar.checkout.attempts");
        this.accepted = meters.counter("bazaar.checkout.results", "result", "accepted");
        this.replayed = meters.counter("bazaar.checkout.results", "result", "replay");
        this.rejected = meters.counter("bazaar.checkout.results", "result", "rejected");
        this.confirmed = meters.counter("bazaar.saga.completed", "outcome", "CONFIRMED");
        this.paymentFailed = meters.counter("bazaar.saga.completed", "outcome", "PAYMENT_FAILED");
        this.cancelled = meters.counter("bazaar.saga.completed", "outcome", "CANCELLED");
        this.failed = meters.counter("bazaar.saga.completed", "outcome", "FAILED");
        meters.gauge("bazaar.saga.active", jdbc, CheckoutMetrics::active);
    }

    public void attempt() {
        attempts.increment();
    }

    public void accepted() {
        accepted.increment();
    }

    public void replay() {
        replayed.increment();
    }

    public void rejected() {
        rejected.increment();
    }

    public void complete(String outcome) {
        switch (outcome) {
            case "CONFIRMED" -> confirmed.increment();
            case "PAYMENT_FAILED" -> paymentFailed.increment();
            case "CANCELLED" -> cancelled.increment();
            case "FAILED" -> failed.increment();
            default -> {
            }
        }
    }

    private static double active(JdbcTemplate jdbc) {
        try {
            Integer count = jdbc.queryForObject(ACTIVE, Integer.class);
            return count == null ? 0 : count;
        } catch (RuntimeException exception) {
            return 0;
        }
    }
}

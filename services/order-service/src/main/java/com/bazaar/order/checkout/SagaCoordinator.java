package com.bazaar.order.checkout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bazaar.order.messaging.EventEnvelope;
import com.bazaar.order.messaging.EventRules;
import com.bazaar.order.outbox.ProcessedEvents;

import tools.jackson.databind.JsonNode;

@Service
public class SagaCoordinator {

    private final OrderTransactions transactions;
    private final ProcessedEvents processed;
    private final Clock clock;
    private final Duration resendAfter;
    private final Duration giveUpAfter;

    public SagaCoordinator(
            OrderTransactions transactions,
            ProcessedEvents processed,
            Clock clock,
            @Value("${bazaar.saga.resend-after:PT20S}") Duration resendAfter,
            @Value("${bazaar.saga.give-up-after:PT2M}") Duration giveUpAfter) {
        this.transactions = transactions;
        this.processed = processed;
        this.clock = clock;
        this.resendAfter = resendAfter;
        this.giveUpAfter = giveUpAfter;
    }

    @Transactional
    public void accept(EventEnvelope event) {
        EventRules.require(event);
        if (!processed.record("order-saga", event)) {
            return;
        }
        Instant now = clock.instant();
        switch (event.eventType()) {
            case "inventory.reserved" -> transactions.onReserved(
                    event.correlationId(), uuid(event.payload(), "productId"), uuid(event.aggregateId()), now);
            case "inventory.reservation-failed" -> transactions.onReservationFailed(event.correlationId(), now);
            case "inventory.committed" -> transactions.onCommitted(uuid(event.aggregateId()), now);
            case "inventory.released" -> transactions.onReleased(uuid(event.aggregateId()), now);
            case "inventory.commit-failed" -> transactions.onCommitFailed(
                    event.correlationId(), uuid(event.payload(), "reservationId"), now);
            case "payment.succeeded" -> transactions.onPaymentSucceeded(
                    uuid(event.payload(), "orderId"), uuid(event.aggregateId()), now);
            case "payment.failed", "payment.cancelled" -> transactions.onPaymentFailed(
                    uuid(event.payload(), "orderId"), uuid(event.aggregateId()), now);
            case "payment.refunded" -> transactions.onPaymentRefunded(uuid(event.payload(), "orderId"), now);
            default -> {
            }
        }
    }

    public void recover() {
        Instant now = clock.instant();
        transactions.recover(now.minus(resendAfter), now.minus(giveUpAfter));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.get(field).asString());
    }
}

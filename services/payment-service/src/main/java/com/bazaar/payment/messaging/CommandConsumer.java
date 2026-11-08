package com.bazaar.payment.messaging;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bazaar.payment.outbox.OutboxStore;
import com.bazaar.payment.outbox.ProcessedEvents;
import com.bazaar.payment.pay.CreateOutcome;
import com.bazaar.payment.pay.PaymentAccess;
import com.bazaar.payment.pay.PaymentResponse;
import com.bazaar.payment.pay.PaymentTransactions;

import tools.jackson.databind.ObjectMapper;

@Component
public class CommandConsumer {

    private final ObjectMapper objectMapper;
    private final ProcessedEvents processed;
    private final PaymentTransactions transactions;
    private final OutboxStore outbox;
    private final Clock clock;

    public CommandConsumer(
            ObjectMapper objectMapper,
            ProcessedEvents processed,
            PaymentTransactions transactions,
            OutboxStore outbox,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.processed = processed;
        this.transactions = transactions;
        this.outbox = outbox;
        this.clock = clock;
    }

    @KafkaListener(topics = "${bazaar.payment.commands-topic}", groupId = "payment-commands")
    @Transactional
    public void onCommand(ConsumerRecord<String, String> record) {
        EventEnvelope event = objectMapper.readValue(record.value(), EventEnvelope.class);
        EventRules.require(event);
        if (!event.aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("Kafka key must be the aggregate id.");
        }
        if (!processed.record("payment-commands", event)) {
            return;
        }
        switch (event.eventType()) {
            case "payment.requested" -> request(event);
            case "payment.refund-requested" -> refund(event);
            case "payment.cancel-requested" -> cancel(event);
            default -> {
            }
        }
    }

    private void request(EventEnvelope event) {
        UUID orderId = UUID.fromString(event.payload().get("orderId").asString());
        String customerId = event.payload().get("customerId").asString();
        BigDecimal amount = new BigDecimal(event.payload().get("amount").asString());
        String currency = event.payload().get("currency").asString();
        String outcome = event.payload().get("outcome").asString();
        String target = "SUCCESS".equals(outcome) ? "SUCCEEDED" : "FAILED";
        String attempt = "SUCCESS".equals(outcome) ? "SIMULATE_SUCCESS" : "SIMULATE_FAILURE";
        PaymentAccess access = new PaymentAccess(customerId, Set.of());
        String key = "p" + orderId.toString().replace("-", "");
        CreateOutcome created;
        try {
            created = transactions.create(access, key, hash(orderId, amount, currency, customerId), orderId, amount, currency, clock.instant());
        } catch (DataIntegrityViolationException conflict) {
            created = new CreateOutcome(transactions.replay(access, key, hash(orderId, amount, currency, customerId)), false);
        }
        PaymentResponse payment = created.payment();
        if (!target.equals(payment.status())) {
            transactions.change(access, payment.id(), target, attempt, clock.instant());
            return;
        }
        reply(target.equals("SUCCEEDED") ? "payment.succeeded" : "payment.failed", payment, clock.instant());
    }

    private void refund(EventEnvelope event) {
        UUID paymentId = UUID.fromString(event.payload().get("paymentId").asString());
        String customerId = event.payload().get("customerId").asString();
        PaymentAccess access = new PaymentAccess(customerId, Set.of());
        if (!transactions.refund(access, paymentId, clock.instant())) {
            PaymentResponse payment = transactions.get(access, paymentId);
            reply("payment.refunded", payment, clock.instant());
        }
    }

    private void cancel(EventEnvelope event) {
        UUID paymentId = UUID.fromString(event.payload().get("paymentId").asString());
        String customerId = event.payload().get("customerId").asString();
        PaymentAccess access = new PaymentAccess(customerId, Set.of());
        PaymentResponse payment = transactions.get(access, paymentId);
        if (!"CANCELLED".equals(payment.status())) {
            transactions.change(access, paymentId, "CANCELLED", "CANCEL", clock.instant());
            return;
        }
        reply("payment.cancelled", payment, clock.instant());
    }

    private void reply(String eventType, PaymentResponse payment, java.time.Instant now) {
        outbox.add(eventType, "payment", payment.id().toString(), payment.id(), java.util.Map.of(
                "orderId", payment.orderId().toString(),
                "customerId", payment.payerId(),
                "amount", payment.amount().toPlainString(),
                "currency", payment.currency(),
                "status", payment.status()), now);
    }

    private static String hash(UUID orderId, BigDecimal amount, String currency, String payer) {
        try {
            String source = orderId + "|" + amount.toPlainString() + "|" + currency + "|" + payer;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}

package com.bazaar.inventory.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bazaar.inventory.outbox.OutboxStore;
import com.bazaar.inventory.outbox.ProcessedEvents;
import com.bazaar.inventory.stock.InventoryMetrics;
import com.bazaar.inventory.stock.InventoryTransactions;
import com.bazaar.inventory.stock.InventoryTransactions.Requested;
import com.bazaar.inventory.stock.StockShortException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class CommandConsumer {

    private final ObjectMapper objectMapper;
    private final ProcessedEvents processed;
    private final InventoryTransactions transactions;
    private final OutboxStore outbox;
    private final InventoryMetrics metrics;
    private final Clock clock;
    private final Duration ttl;

    public CommandConsumer(
            ObjectMapper objectMapper,
            ProcessedEvents processed,
            InventoryTransactions transactions,
            OutboxStore outbox,
            InventoryMetrics metrics,
            Clock clock,
            @Value("${bazaar.inventory.reservation-ttl:PT15M}") Duration ttl) {
        this.objectMapper = objectMapper;
        this.processed = processed;
        this.transactions = transactions;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
        this.ttl = ttl;
    }

    @KafkaListener(topics = "${bazaar.inventory.commands-topic}", groupId = "inventory-commands")
    @Transactional
    public void onCommand(ConsumerRecord<String, String> record) {
        EventEnvelope event = objectMapper.readValue(record.value(), EventEnvelope.class);
        EventRules.require(event);
        if (!event.aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("Kafka key must be the aggregate id.");
        }
        if (!processed.record("inventory-commands", event)) {
            return;
        }
        Instant now = clock.instant();
        switch (event.eventType()) {
            case "inventory.reservation-requested" -> reserve(event, now);
            case "inventory.commit-requested" -> commit(event, now);
            case "inventory.release-requested" -> release(event, now);
            default -> {
            }
        }
    }

    private void reserve(EventEnvelope event, Instant now) {
        UUID orderId = uuid(event.payload(), "orderId");
        String customerId = event.payload().get("customerId").asString();
        List<Requested> items = new ArrayList<>();
        for (JsonNode item : event.payload().get("items")) {
            items.add(new Requested(UUID.fromString(item.get("productId").asString()), item.get("quantity").asInt()));
        }
        int attempt = 0;
        while (true) {
            try {
                transactions.reserveForSaga(orderId, customerId, items, now, now.plus(ttl));
                metrics.reserved();
                return;
            } catch (StockShortException exception) {
                metrics.rejected();
                outbox.add("inventory.reservation-failed", "inventory", orderId.toString(), orderId, java.util.Map.of(
                        "orderId", orderId.toString(),
                        "reason", exception.getMessage()), now);
                return;
            } catch (RuntimeException exception) {
                if (!deadlock(exception) || attempt == 2) {
                    throw exception;
                }
                attempt++;
            }
        }
    }

    private static boolean deadlock(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql && "40P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private void commit(EventEnvelope event, Instant now) {
        UUID orderId = uuid(event.payload(), "orderId");
        UUID reservationId = uuid(event.payload(), "reservationId");
        String customerId = event.payload().get("customerId").asString();
        try {
            if (!transactions.commitForSaga(customerId, reservationId, now)) {
                reply("inventory.committed", reservationId, orderId, now);
            }
        } catch (StockShortException exception) {
            outbox.add("inventory.commit-failed", "inventory", orderId.toString(), orderId, java.util.Map.of(
                    "orderId", orderId.toString(),
                    "reservationId", reservationId.toString(),
                    "reason", exception.getMessage()), now);
        }
    }

    private void release(EventEnvelope event, Instant now) {
        UUID orderId = uuid(event.payload(), "orderId");
        UUID reservationId = uuid(event.payload(), "reservationId");
        try {
            if (!transactions.releaseForSaga(reservationId, now)) {
                reply("inventory.released", reservationId, orderId, now);
            }
        } catch (StockShortException exception) {
            outbox.add("inventory.commit-failed", "inventory", orderId.toString(), orderId, java.util.Map.of(
                    "orderId", orderId.toString(),
                    "reservationId", reservationId.toString(),
                    "reason", exception.getMessage()), now);
        }
    }

    private void reply(String eventType, UUID reservationId, UUID orderId, Instant now) {
        outbox.add(eventType, "inventory", reservationId.toString(), orderId, java.util.Map.of(
                "orderId", orderId.toString(),
                "reservationId", reservationId.toString()), now);
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.get(field).asString());
    }
}

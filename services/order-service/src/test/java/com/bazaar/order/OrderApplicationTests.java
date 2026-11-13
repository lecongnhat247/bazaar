package com.bazaar.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.bazaar.order.checkout.CheckoutResilience;
import com.bazaar.order.checkout.SagaCoordinator;
import com.bazaar.order.messaging.EventEnvelope;
import com.bazaar.order.messaging.OrderEventConsumer;
import com.bazaar.order.outbox.OutboxPublisher;
import com.bazaar.order.outbox.OutboxStore;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@SpringBootTest(properties = {
        "bazaar.security.jwt.remote=false",
        "bazaar.outbox.poll-delay=3600000",
        "bazaar.saga.recover-delay=3600000",
        "bazaar.saga.resend-after=PT24H",
        "bazaar.saga.give-up-after=PT48H"
})
@AutoConfigureMockMvc
@Import({OrderApplicationTests.DatabaseTestConfiguration.class, OrderApplicationTests.TestJwtConfiguration.class})
class OrderApplicationTests {

    private static final UUID LAMP = UUID.fromString("8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001");
    private static final UUID SHIRT = UUID.fromString("8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1002");
    private static final AtomicInteger RESERVES = new AtomicInteger();
    private static final AtomicInteger COMMITS = new AtomicInteger();
    private static final AtomicInteger RELEASES = new AtomicInteger();
    private static volatile String fault = "NONE";
    private static final AtomicInteger CATALOG_CALLS = new AtomicInteger();
    private static final AtomicInteger SLOW_IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger SLOW_PEAK = new AtomicInteger();
    private static final HttpServer DOWNSTREAM = startDownstream();
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    static {
        KAFKA.withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR", "1");
        KAFKA.withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR", "1");
        KAFKA.start();
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderEventConsumer orderEvents;

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private OutboxStore outboxStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SagaCoordinator saga;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CheckoutResilience resilience;

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort();
        registry.add("bazaar.order.catalog-url", () -> base);
        registry.add("bazaar.order.inventory-url", () -> base);
        registry.add("bazaar.order.payment-url", () -> base);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    @BeforeEach
    void resetDownstream() {
        fault = "NONE";
        RESERVES.set(0);
        COMMITS.set(0);
        RELEASES.set(0);
        CATALOG_CALLS.set(0);
        SLOW_IN_FLIGHT.set(0);
        SLOW_PEAK.set(0);
        resilience.reset();
    }

    @Test
    void applicationContextStarts() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void kafkaHealthIsSeparateFromReadiness() throws Exception {
        mockMvc.perform(get("/actuator/health/kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void duplicateDeliveryIsAppliedOnce() {
        UUID eventId = UUID.randomUUID();
        String aggregateId = UUID.randomUUID().toString();
        String body = """
                {"eventId":"%s","eventType":"order.foundation-checked","eventVersion":1,"aggregateId":"%s","aggregateType":"order","occurredAt":"2026-11-07T00:00:00Z","correlationId":"22222222-2222-4222-8222-222222222222","producer":"order-service"}
                """.formatted(eventId, aggregateId);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("bazaar.order.events", 0, 0L, aggregateId, body);
        orderEvents.onOrderEvent(record);
        orderEvents.onOrderEvent(record);
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class,
                eventId);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void confirmedOrderIsPublishedOnceWhenTheOutboxRowIsRetried() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-outbox"))
                        .header("Idempotency-Key", "order-key-outbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_INVENTORY"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND status = 'PENDING'",
                Integer.class,
                id);
        assertThat(pending).isEqualTo(2);
        outbox.publishPending();
        waitUntilProcessed(id, 1);
        jdbc.update("UPDATE outbox SET status = 'PENDING', claimed_at = NULL, published_at = NULL WHERE aggregate_id = ?", id);
        outbox.publishPending();
        Thread.sleep(1000);
        assertThat(processed(id)).isEqualTo(1);
    }

    @Test
    void rejectedCheckoutWritesNoOutboxRow() throws Exception {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
        fault = "CATALOG";
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-outbox-stock"))
                        .header("Idempotency-Key", "order-key-outbox-stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SHIRT, 1, "SUCCESS")))
                .andExpect(status().isServiceUnavailable());
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void catalogOutageOpensTheCircuitAndWritesNoOrder() throws Exception {
        fault = "CATALOG";
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM customer_order", Integer.class);
        for (int attempt = 0; attempt < 8; attempt++) {
            mockMvc.perform(post("/api/v1/orders/checkout")
                            .with(customer("buyer-circuit"))
                            .header("Idempotency-Key", "order-key-circuit-" + attempt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(LAMP, 1, "SUCCESS")))
                    .andExpect(status().isServiceUnavailable());
        }
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM customer_order", Integer.class);
        assertThat(after).isEqualTo(before);
        assertThat(CATALOG_CALLS.get()).isBetween(5, 12);
    }

    @Test
    void missingProductIsNotRetried() throws Exception {
        fault = "MISSING";
        for (int attempt = 0; attempt < 6; attempt++) {
            mockMvc.perform(post("/api/v1/orders/checkout")
                            .with(customer("buyer-missing"))
                            .header("Idempotency-Key", "order-key-missing-" + attempt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(LAMP, 1, "SUCCESS")))
                    .andExpect(status().isConflict());
        }
        assertThat(CATALOG_CALLS.get()).isEqualTo(6);
        fault = "NONE";
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-missing"))
                        .header("Idempotency-Key", "order-key-missing-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS")))
                .andExpect(status().isCreated());
    }

    @Test
    void inventoryOutageRejectsTheCampaignCheckout() throws Exception {
        fault = "INVENTORY";
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM customer_order", Integer.class);
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-inventory-down"))
                        .header("Idempotency-Key", "order-key-inventory-down")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS", UUID.fromString("8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d2001"))))
                .andExpect(status().isServiceUnavailable());
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM customer_order", Integer.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void downstreamBulkheadCapsConcurrentCatalogCalls() throws Exception {
        fault = "SLOW";
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger unavailable = new AtomicInteger();
        for (int attempt = 0; attempt < threads; attempt++) {
            String key = "order-key-bulk-" + attempt;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    int code = mockMvc.perform(post("/api/v1/orders/checkout")
                                    .with(customer("buyer-bulk"))
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(LAMP, 1, "SUCCESS")))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (code == 503) {
                        unavailable.incrementAndGet();
                    }
                } catch (Exception exception) {
                    unavailable.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(unavailable.get()).isEqualTo(threads);
        assertThat(SLOW_PEAK.get()).isBetween(1, 8);
        assertThat(CATALOG_CALLS.get()).isLessThanOrEqualTo(8);
    }

    private void waitUntilProcessed(String orderId, int expected) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (processed(orderId) == expected) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(processed(orderId)).isEqualTo(expected);
    }

    private int processed(String orderId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE event_id IN (SELECT event_id FROM outbox WHERE aggregate_id = ?)
                """, Integer.class, orderId);
        return count == null ? 0 : count;
    }

    @Test
    void consumerRejectsAKeyThatIsNotTheAggregate() {
        String body = """
                {"eventId":"11111111-1111-4111-8111-111111111111","eventType":"order.foundation-checked","eventVersion":1,"aggregateId":"order-1","aggregateType":"order","occurredAt":"2026-11-07T00:00:00Z","correlationId":"22222222-2222-4222-8222-222222222222","producer":"order-service"}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("bazaar.order.events", 0, 0L, "other-key", body);
        assertThatThrownBy(() -> orderEvents.onOrderEvent(record))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicationGivesUpAfterTheAttemptLimit() {
        UUID correlation = UUID.randomUUID();
        outboxStore.add(
                "order.failed",
                "order",
                UUID.randomUUID().toString(),
                correlation,
                Map.of("status", "FAILED"),
                Instant.parse("2026-11-08T00:00:00Z"),
                "bazaar.order.events");
        UUID id = jdbc.queryForObject("SELECT id FROM outbox WHERE correlation_id = ?", UUID.class, correlation);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(outboxStore.markFailed(id, "broker down", 5, Instant.now().plusSeconds(60))).isEqualTo("PENDING");
        }
        assertThat(outboxStore.claim(20, Instant.now())).extracting("id").doesNotContain(id);
        assertThat(outboxStore.markFailed(id, "broker down", 5, Instant.now())).isEqualTo("DEAD");
        Integer dead = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE id = ? AND status = 'DEAD' AND retry_count = 5",
                Integer.class,
                id);
        assertThat(dead).isEqualTo(1);
    }

    @Test
    void invalidEventIsCopiedToTheDeadLetterTopic() throws Exception {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("group.id", "order-dlq-test");
        properties.put("key.deserializer", StringDeserializer.class.getName());
        properties.put("value.deserializer", StringDeserializer.class.getName());
        properties.put("auto.offset.reset", "earliest");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of("bazaar.dlq"));
            consumer.poll(Duration.ofMillis(1000));
            KafkaTemplate<String, String> kafka = applicationContext.getBean(KafkaTemplate.class);
            kafka.send("bazaar.order.events", "poison-key", "not-json").get(10, TimeUnit.SECONDS);
            String found = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (found == null && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains("not-json")) {
                        found = record.value();
                    }
                }
            }
            assertThat(found).contains("not-json").contains("bazaar.order.events");
        }
    }

    @Test
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void livenessAndReadinessAreUp() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void prometheusStaysPublic() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("bazaar_saga_active")));
    }

    @Test
    void infoStaysPublic() throws Exception {
        mockMvc.perform(get("/api/v1/orders/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("order-service"))
                .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unknownApiRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/orders/private"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/orders/checkout").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutUsesCatalogPriceAndCanBeReplayed() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-ok"))
                        .header("Idempotency-Key", "order-key-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 2, "SUCCESS")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_INVENTORY"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.total").value(50.00))
                .andExpect(jsonPath("$.items[0].unitPrice").value(25.00))
                .andExpect(jsonPath("$.items[0].name").value("Desk lamp"))
                .andExpect(jsonPath("$.history[0].status").value("PENDING_INVENTORY"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-ok"))
                        .header("Idempotency-Key", "order-key-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 2, "SUCCESS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-ok"))
                        .header("Idempotency-Key", "order-key-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/orders/" + id + "/cancel").with(customer("buyer-ok")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void checkoutChargesTheCampaignPrice() throws Exception {
        UUID campaignId = UUID.fromString("8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d2001");
        MvcResult created = mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-campaign"))
                        .header("Idempotency-Key", "order-key-campaign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS", campaignId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(9.00))
                .andExpect(jsonPath("$.items[0].unitPrice").value(9.00))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String payload = jdbc.queryForObject(
                "SELECT payload FROM outbox WHERE aggregate_id = ? AND event_type = 'inventory.reservation-requested'",
                String.class,
                id);
        assertThat(payload).contains(campaignId.toString());
        Integer stored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_order WHERE id = ? AND campaign_id = ?",
                Integer.class,
                UUID.fromString(id),
                campaignId);
        assertThat(stored).isEqualTo(1);

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-campaign"))
                        .header("Idempotency-Key", "order-key-campaign-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SHIRT, 1, "SUCCESS", campaignId)))
                .andExpect(status().isConflict());
    }

    @Test
    void failedPaymentReleasesStock() throws Exception {
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-fail"))
                        .header("Idempotency-Key", "order-key-fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "FAILURE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_INVENTORY"))
                .andExpect(jsonPath("$.total").value(25.00));
    }

    @Test
    void insufficientStockDoesNotCreateAnOrder() throws Exception {
        fault = "STOCK";
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-stock"))
                        .header("Idempotency-Key", "order-key-stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + LAMP + "\",\"quantity\":1},{\"productId\":\"" + SHIRT
                                + "\",\"quantity\":1}],\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_INVENTORY"));

        mockMvc.perform(get("/api/v1/orders").with(customer("buyer-stock")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void catalogOutageCanBeRetried() throws Exception {
        fault = "CATALOG";
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-retry"))
                        .header("Idempotency-Key", "order-key-retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS")))
                .andExpect(status().isServiceUnavailable());
        assertThat(RESERVES.get()).isZero();

        fault = "NONE";
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-retry"))
                        .header("Idempotency-Key", "order-key-retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_INVENTORY"));
    }

    @Test
    void pendingOrderCanBeCancelledBeforeInventoryReserves() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-hold"))
                        .header("Idempotency-Key", "order-key-hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_INVENTORY"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/v1/orders/" + id + "/cancel").with(customer("buyer-hold")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void paymentFailureReleasesTheReservationAndConfirmsOnlyAfterCommit() throws Exception {
        String failed = checkout("buyer-saga-fail", "saga-fail-1", "FAILURE");
        UUID reservationId = UUID.randomUUID();
        saga.accept(reserved(failed, LAMP, reservationId));
        mockMvc.perform(get("/api/v1/orders/" + failed).with(customer("buyer-saga-fail")))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        saga.accept(payment(failed, "payment.failed"));
        mockMvc.perform(get("/api/v1/orders/" + failed).with(customer("buyer-saga-fail")))
                .andExpect(jsonPath("$.status").value("COMPENSATING"));
        saga.accept(stock(reservationId, "inventory.released"));
        mockMvc.perform(get("/api/v1/orders/" + failed).with(customer("buyer-saga-fail")))
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"));

        String confirmed = checkout("buyer-saga-ok", "saga-ok-1", "SUCCESS");
        UUID committed = UUID.randomUUID();
        saga.accept(reserved(confirmed, LAMP, committed));
        saga.accept(payment(confirmed, "payment.succeeded"));
        mockMvc.perform(get("/api/v1/orders/" + confirmed).with(customer("buyer-saga-ok")))
                .andExpect(jsonPath("$.status").value("PENDING_INVENTORY_COMMIT"));
        saga.accept(reserved(confirmed, LAMP, committed));
        mockMvc.perform(get("/api/v1/orders/" + confirmed).with(customer("buyer-saga-ok")))
                .andExpect(jsonPath("$.status").value("PENDING_INVENTORY_COMMIT"));
        saga.accept(stock(committed, "inventory.committed"));
        mockMvc.perform(get("/api/v1/orders/" + confirmed).with(customer("buyer-saga-ok")))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        Integer commits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'inventory.commit-requested'",
                Integer.class,
                committed.toString());
        assertThat(commits).isEqualTo(1);
    }

    @Test
    void reservationFailureDoesNotRequestPayment() throws Exception {
        String id = checkout("buyer-saga-stock", "saga-stock-1", "SUCCESS");
        saga.accept(failedReservation(id));
        mockMvc.perform(get("/api/v1/orders/" + id).with(customer("buyer-saga-stock")))
                .andExpect(jsonPath("$.status").value("FAILED"));
        Integer payments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'payment.requested'",
                Integer.class,
                id);
        assertThat(payments).isZero();
    }

    @Test
    void commitFailureRefundsThePayment() throws Exception {
        String id = checkout("buyer-saga-refund", "saga-refund-1", "SUCCESS");
        UUID reservationId = UUID.randomUUID();
        saga.accept(reserved(id, LAMP, reservationId));
        saga.accept(payment(id, "payment.succeeded"));
        UUID paymentId = jdbc.queryForObject("SELECT payment_id FROM customer_order WHERE id = ?::uuid", UUID.class, id);
        saga.accept(commitFailed(id, reservationId));
        saga.accept(commitFailed(id, reservationId));
        saga.accept(commitFailed(id, reservationId));
        mockMvc.perform(get("/api/v1/orders/" + id).with(customer("buyer-saga-refund")))
                .andExpect(jsonPath("$.status").value("COMPENSATING"));
        Integer refunds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE event_type = 'payment.refund-requested' AND aggregate_id = ?",
                Integer.class,
                paymentId.toString());
        assertThat(refunds).isEqualTo(1);
        saga.accept(refunded(id, paymentId));
        saga.accept(stock(reservationId, "inventory.released"));
        mockMvc.perform(get("/api/v1/orders/" + id).with(customer("buyer-saga-refund")))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void recoveryResendsTheReservationCommand() throws Exception {
        String id = checkout("buyer-saga-recover", "saga-recover-1", "SUCCESS");
        jdbc.update("UPDATE outbox SET status = 'PUBLISHED' WHERE aggregate_id = ? AND event_type = 'inventory.reservation-requested'", id);
        jdbc.update("UPDATE customer_order SET updated_at = ? WHERE id = ?::uuid", java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofHours(30))), id);
        saga.recover();
        Integer commands = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'inventory.reservation-requested'",
                Integer.class,
                id);
        assertThat(commands).isEqualTo(2);
    }

    private String checkout(String customer, String key, String outcome) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer(customer))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, outcome)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private EventEnvelope reserved(String orderId, UUID productId, UUID reservationId) {
        return event("inventory.reserved", reservationId.toString(), UUID.fromString(orderId),
                "{\"productId\":\"" + productId + "\",\"orderId\":\"" + orderId + "\",\"quantity\":1,\"status\":\"HELD\"}");
    }

    private EventEnvelope failedReservation(String orderId) {
        return event("inventory.reservation-failed", orderId, UUID.fromString(orderId),
                "{\"orderId\":\"" + orderId + "\",\"reason\":\"Not enough stock is available.\"}");
    }

    private EventEnvelope payment(String orderId, String type) {
        UUID paymentId = UUID.randomUUID();
        return event(type, paymentId.toString(), paymentId,
                "{\"orderId\":\"" + orderId + "\",\"amount\":\"25.00\",\"currency\":\"USD\",\"status\":\"" + type + "\"}");
    }

    private EventEnvelope stock(UUID reservationId, String type) {
        return event(type, reservationId.toString(), reservationId, "{\"reservationId\":\"" + reservationId + "\"}");
    }

    private EventEnvelope commitFailed(String orderId, UUID reservationId) {
        return event("inventory.commit-failed", orderId, UUID.fromString(orderId),
                "{\"orderId\":\"" + orderId + "\",\"reservationId\":\"" + reservationId + "\"}");
    }

    private EventEnvelope refunded(String orderId, UUID paymentId) {
        return event("payment.refunded", paymentId.toString(), paymentId,
                "{\"orderId\":\"" + orderId + "\",\"status\":\"REFUNDED\"}");
    }

    private EventEnvelope event(String type, String aggregateId, UUID correlationId, String payload) {
        try {
            return new EventEnvelope(
                    UUID.randomUUID(),
                    type,
                    1,
                    aggregateId,
                    "inventory",
                    null,
                    Instant.parse("2026-11-08T00:00:00Z"),
                    correlationId,
                    null,
                    "test",
                    objectMapper.readTree(payload));
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    @Test
    void concurrentDuplicateCheckoutCreatesOneOrder() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    int status = mockMvc.perform(post("/api/v1/orders/checkout")
                                    .with(customer("buyer-dup"))
                                    .header("Idempotency-Key", "order-key-dup-race")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(LAMP, 1, "SUCCESS")))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (status == 201) {
                        created.incrementAndGet();
                    } else if (status == 200) {
                        replayed.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Exception exception) {
                    failed.incrementAndGet();
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        Integer orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_order WHERE idempotency_key = ?",
                Integer.class,
                "order-key-dup-race");
        assertThat(orders).isEqualTo(1);
        assertThat(created.get()).isEqualTo(1);
        assertThat(replayed.get()).isEqualTo(threads - 1);
        assertThat(failed.get()).isZero();
    }

    @Test
    void concurrentCancelKeepsOneStatusHistory() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-cancel-race"))
                        .header("Idempotency-Key", "order-key-cancel-race")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    int status = mockMvc.perform(post("/api/v1/orders/" + id + "/cancel").with(customer("buyer-cancel-race")))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (status == 200) {
                        ok.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Exception exception) {
                    failed.incrementAndGet();
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM customer_order WHERE id = ?",
                String.class,
                UUID.fromString(id));
        Integer duplicateSeq = jdbc.queryForObject("""
                SELECT COUNT(*) - COUNT(DISTINCT seq)
                FROM order_status_history
                WHERE order_id = ?
                """, Integer.class, UUID.fromString(id));
        assertThat(orderStatus).isEqualTo("CANCELLED");
        assertThat(duplicateSeq).isZero();
        assertThat(ok.get() + failed.get()).isEqualTo(2);
    }

    @Test
    void anotherCustomerCannotReadTheOrder() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-owner"))
                        .header("Idempotency-Key", "order-key-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/orders/" + id).with(customer("buyer-other")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/" + id).with(admin("buyer-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void aSellerSeesOnlyTheirOwnLines() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-sales"))
                        .header("Idempotency-Key", "order-key-sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        jdbc.update("""
                INSERT INTO order_item (
                    id, order_id, product_id, seller_id, sku, product_name, unit_price, quantity, line_total, reservation_id)
                VALUES (?, ?, ?, 'seller-2', 'OTHER-SKU', 'Other shirt', 10.00, 1, 10.00, ?)
                """, UUID.randomUUID(), UUID.fromString(id), UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(get("/api/v1/orders/sales").param("q", "OTHER-SKU").with(seller("seller-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(1))
                .andExpect(jsonPath("$.orders[0].id").value(id))
                .andExpect(jsonPath("$.orders[0].items.length()").value(1))
                .andExpect(jsonPath("$.orders[0].items[0].sellerId").value("seller-2"))
                .andExpect(jsonPath("$.orders[0].items[0].sku").value("OTHER-SKU"))
                .andExpect(jsonPath("$.orders[0].paymentId").doesNotExist())
                .andExpect(jsonPath("$.orders[0].total").doesNotExist());
        mockMvc.perform(get("/api/v1/orders/sales").param("q", "OTHER-SKU").with(seller("seller-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(0));
        mockMvc.perform(get("/api/v1/orders/sales").with(customer("buyer-sales")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/sales").with(admin("admin-sales")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/" + id).with(seller("seller-2")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/sales").param("status", "NOPE").with(seller("seller-2")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidCheckoutIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-bad"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Idempotency-Key is invalid"));

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-bad"))
                        .header("Idempotency-Key", "order-key-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + LAMP + "\",\"quantity\":1},{\"productId\":\"" + LAMP
                                + "\",\"quantity\":1}],\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyStaffCanMonitorOrders() throws Exception {
        mockMvc.perform(get("/api/v1/orders/monitor").with(customer("buyer-monitor")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/monitor").with(operator("operator-monitor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0));
    }

    // ------------------------------------------------------------------
    // Phase 5 M4: marketplace workflows (fulfillment, coupons, commission, ledger, settlement)
    // ------------------------------------------------------------------

    @Test
    void multiSellerOrderKeepsEachLinesSellerAndEachSellerSeesOnlyTheirOwn() throws Exception {
        String id = confirmed("buyer-multi", "multi-key-0001", null, LAMP, SHIRT);

        assertThat(jdbc.queryForList(
                "SELECT seller_id FROM order_item WHERE order_id = ?::uuid ORDER BY seller_id", String.class, id))
                .containsExactly("seller-1", "seller-2");
        mockMvc.perform(get("/api/v1/orders/" + id).with(customer("buyer-multi")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.total").value(44.50))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.fulfillments.length()").value(2))
                .andExpect(jsonPath("$.fulfillments[0].sellerId").value("seller-1"))
                .andExpect(jsonPath("$.fulfillments[1].sellerId").value("seller-2"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("PENDING"));

        mockMvc.perform(get("/api/v1/orders/sales").param("q", id).with(seller("seller-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(1))
                .andExpect(jsonPath("$.orders[0].items.length()").value(1))
                .andExpect(jsonPath("$.orders[0].items[0].sku").value("LAMP"))
                .andExpect(jsonPath("$.orders[0].items[0].sellerId").value("seller-1"))
                .andExpect(jsonPath("$.orders[0].sellerTotal").value(25.00))
                .andExpect(jsonPath("$.orders[0].fulfillment.sellerId").value("seller-1"));
        mockMvc.perform(get("/api/v1/orders/sales").param("q", id).with(seller("seller-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(1))
                .andExpect(jsonPath("$.orders[0].items.length()").value(1))
                .andExpect(jsonPath("$.orders[0].items[0].sku").value("SHIRT"))
                .andExpect(jsonPath("$.orders[0].sellerTotal").value(19.50))
                .andExpect(jsonPath("$.orders[0].fulfillment.sellerId").value("seller-2"))
                .andExpect(jsonPath("$.orders[0].couponCode").doesNotExist());
    }

    @Test
    void sellersCannotReadOrChangeAnotherSellersData() throws Exception {
        String id = confirmed("buyer-iso", "iso-key-00001", null, LAMP); // seller-1 only

        assertThat(fulfil("seller-2", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(fulfil("buyer-iso", id, "PACKED", null, null, customer("buyer-iso")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(fulfil("admin-iso", id, "PACKED", null, null, admin("admin-iso")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(fulfil("operator-iso", id, "PACKED", null, null, operator("operator-iso")).getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM fulfillment WHERE order_id = ?::uuid AND seller_id = 'seller-1'",
                String.class, id)).isEqualTo("PENDING");

        mockMvc.perform(get("/api/v1/orders/sales").param("q", id).with(seller("seller-2")))
                .andExpect(jsonPath("$.totalOrders").value(0));
        String other = mockMvc.perform(get("/api/v1/orders/finance/me").with(seller("seller-2")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(other, "$.entries[?(@.sellerId != 'seller-2')]"))
                .isEmpty();
        assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(other, "$.entries[?(@.account == 'PLATFORM_REVENUE')]"))
                .isEmpty();
        assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(other, "$.entries[?(@.orderId == '" + id + "')]"))
                .isEmpty();
        String own = mockMvc.perform(get("/api/v1/orders/finance/me").with(seller("seller-1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(own, "$.entries[?(@.orderId == '" + id + "')]"))
                .isNotEmpty();

        mockMvc.perform(get("/api/v1/orders/finance/me").with(customer("buyer-iso"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/finance/me").with(admin("admin-iso"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/finance/ledger").with(seller("seller-1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/finance/reconciliation").with(seller("seller-1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/coupons").with(seller("seller-1"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/coupons").with(customer("buyer-iso"))).andExpect(status().isForbidden());
    }

    @Test
    void sellersFulfilTheirOwnGroupIndependentlyAndTheOrderIsPartiallyShipped() throws Exception {
        String id = confirmed("buyer-partial", "partial-key-01", null, LAMP, SHIRT);

        assertThat(fulfil("seller-1", id, "SHIPPED", "TRACK-AAA1", null).getResponse().getStatus()).isEqualTo(409);
        assertThat(fulfil("seller-1", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(fulfil("seller-1", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(fulfil("seller-1", id, "SHIPPED", null, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(fulfil("seller-1", id, "SHIPPED", "bad ref!", null).getResponse().getStatus()).isEqualTo(400);
        MvcResult shipped = fulfil("seller-1", id, "SHIPPED", "TRACK-AAA1", null);
        assertThat(shipped.getResponse().getStatus()).isEqualTo(200);
        String shippedJson = shipped.getResponse().getContentAsString();
        assertThat((String) com.jayway.jsonpath.JsonPath.read(shippedJson, "$.shipmentReference"))
                .isEqualTo("TRACK-AAA1");
        assertThat((Object) com.jayway.jsonpath.JsonPath.read(shippedJson, "$.shippedAt")).isNotNull();
        // the repeated PACKED request was idempotent: PENDING, PACKED, SHIPPED
        assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(shippedJson, "$.history")).hasSize(3);

        mockMvc.perform(get("/api/v1/orders/" + id).with(customer("buyer-partial")))
                .andExpect(jsonPath("$.fulfillmentStatus").value("PARTIALLY_SHIPPED"))
                .andExpect(jsonPath("$.fulfillments[0].status").value("SHIPPED"))
                .andExpect(jsonPath("$.fulfillments[1].status").value("PENDING"));
        mockMvc.perform(get("/api/v1/orders/sales").param("q", id).with(seller("seller-2")))
                .andExpect(jsonPath("$.orders[0].fulfillment.status").value("PENDING"));

        assertThat(fulfil("seller-2", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(fulfil("seller-2", id, "SHIPPED", "TRACK-BBB2", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(fulfil("seller-1", id, "DELIVERED", null, null).getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(get("/api/v1/orders/" + id).with(customer("buyer-partial")))
                .andExpect(jsonPath("$.fulfillmentStatus").value("SHIPPED"));
        assertThat(fulfil("seller-2", id, "DELIVERED", null, null).getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(get("/api/v1/orders/" + id).with(customer("buyer-partial")))
                .andExpect(jsonPath("$.fulfillmentStatus").value("DELIVERED"));
        // a delivered group cannot move backwards
        assertThat(fulfil("seller-1", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void ordersCanBeCancelledOnlyBeforeConfirmationAndSellersCancelOnlyBeforeShipping() throws Exception {
        String pending = place("buyer-cancel", "cancel-key-001", null, LAMP);
        mockMvc.perform(post("/api/v1/orders/" + pending + "/cancel").with(customer("buyer-cancel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/v1/orders/" + pending + "/cancel").with(customer("buyer-cancel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fulfillment WHERE order_id = ?::uuid", Integer.class,
                pending)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE order_id = ?::uuid", Integer.class,
                pending)).isZero();

        String id = confirmed("buyer-cancel", "cancel-key-002", null, LAMP, SHIRT);
        mockMvc.perform(post("/api/v1/orders/" + id + "/cancel").with(customer("buyer-cancel")))
                .andExpect(status().isConflict());

        assertThat(fulfil("seller-1", id, "CANCELLED", null, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(fulfil("seller-1", id, "CANCELLED", null, "Out of stock at the warehouse").getResponse()
                .getStatus()).isEqualTo(200);
        List<Map<String, Object>> entries = ledgerOf(id);
        assertThat(net(entries, "seller-1")).isEqualByComparingTo("0.00");
        assertThat(net(entries, "seller-2")).isEqualByComparingTo("19.50");
        assertThat(entries.stream().filter(entry -> "REVERSAL".equals(entry.get("entryType"))).count()).isEqualTo(3);
        mockMvc.perform(get("/api/v1/orders/" + id).with(customer("buyer-cancel")))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.fulfillments[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("PENDING"));
        assertThat(fulfil("seller-1", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(409);

        assertThat(fulfil("seller-2", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(fulfil("seller-2", id, "SHIPPED", "TRACK-CCC3", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(fulfil("seller-2", id, "CANCELLED", null, "Changed my mind").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(net(ledgerOf(id), "seller-2")).isEqualByComparingTo("19.50");
        assertThat(fulfil("seller-2", id, "DELIVERED", null, null).getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(get("/api/v1/orders/" + id).with(customer("buyer-cancel")))
                .andExpect(jsonPath("$.fulfillmentStatus").value("DELIVERED"));
    }

    @Test
    void ordersConfirmedBeforeFulfillmentExistedGetTheirGroupOnFirstSellerAction() throws Exception {
        String id = confirmed("buyer-backfill", "backfill-key-1", null, LAMP);
        jdbc.update("DELETE FROM fulfillment_event WHERE fulfillment_id IN "
                + "(SELECT id FROM fulfillment WHERE order_id = ?::uuid)", id);
        jdbc.update("DELETE FROM fulfillment WHERE order_id = ?::uuid", id);

        assertThat(fulfil("seller-1", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fulfillment WHERE order_id = ?::uuid", Integer.class,
                id)).isEqualTo(1);
        // the sale entries already existed, so the backfill must not duplicate them
        assertThat(ledgerOf(id)).hasSize(3);
        assertThat(fulfil("seller-2", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void couponEligibilityIsDecidedByTheServer() throws Exception {
        for (RequestPostProcessor who : List.of(customer("buyer-elig"), seller("seller-1"), operator("op-elig"))) {
            mockMvc.perform(post("/api/v1/orders/coupons").with(who).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(couponBody("NOPE-CODE", Map.of()))))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/v1/orders/coupons").with(operator("op-elig"))).andExpect(status().isOk());

        // invalid shapes
        assertThat(createCouponStatus("BAD-PERCENT", mapOf("discountType", "PERCENT"))).isEqualTo(400);
        assertThat(createCouponStatus("BAD-FIXED", mapOf("percent", 10))).isEqualTo(400);
        assertThat(createCouponStatus("BAD-WINDOW", mapOf("endsAt", Instant.now().minusSeconds(7200).toString())))
                .isEqualTo(400);
        assertThat(createCouponStatus("BAD-SELLER", mapOf("fundedBy", "SELLER"))).isEqualTo(400);

        createCoupon("ELIG-SELLER2", mapOf("discountType", "PERCENT", "percent", 10, "fixedAmount", null,
                "sellerId", "seller-2"));
        assertThat(createCouponStatus("ELIG-SELLER2", Map.of())).isEqualTo(409);

        assertConflict(attempt("buyer-elig", "elig-key-0001", "ELIG-SELLER2", LAMP), "does not apply");
        mockMvc.perform(get("/api/v1/orders").with(customer("buyer-elig")))
                .andExpect(jsonPath("$.length()").value(0));

        // lower-case code accepted; amounts sent by the browser are ignored
        MvcResult created = mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-elig"))
                        .header("Idempotency-Key", "elig-key-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + LAMP + "\",\"quantity\":1},{\"productId\":\"" + SHIRT
                                + "\",\"quantity\":1}],\"outcome\":\"SUCCESS\",\"couponCode\":\"elig-seller2\","
                                + "\"discount\":99,\"discountTotal\":99,\"total\":0.01}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(42.55))
                .andExpect(jsonPath("$.discountTotal").value(1.95))
                .andExpect(jsonPath("$.couponCode").value("ELIG-SELLER2"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertThat(jdbc.queryForObject(
                "SELECT discount_amount FROM order_item WHERE order_id = ?::uuid AND sku = 'SHIRT'",
                BigDecimal.class, id)).isEqualByComparingTo("1.95");
        assertThat(jdbc.queryForObject(
                "SELECT discount_amount FROM order_item WHERE order_id = ?::uuid AND sku = 'LAMP'",
                BigDecimal.class, id)).isEqualByComparingTo("0.00");

        Instant now = Instant.now();
        createCoupon("ELIG-EXPIRED", mapOf("startsAt", now.minusSeconds(172800).toString(),
                "endsAt", now.minusSeconds(86400).toString()));
        assertConflict(attempt("buyer-elig2", "elig-key-0003", "ELIG-EXPIRED", LAMP), "expired");
        createCoupon("ELIG-FUTURE", mapOf("startsAt", now.plusSeconds(86400).toString(),
                "endsAt", now.plusSeconds(172800).toString()));
        assertConflict(attempt("buyer-elig2", "elig-key-0004", "ELIG-FUTURE", LAMP), "not active");
        createCoupon("ELIG-MIN", mapOf("minSubtotal", 100.00));
        assertConflict(attempt("buyer-elig2", "elig-key-0005", "ELIG-MIN", LAMP), "minimum");
        assertConflict(attempt("buyer-elig2", "elig-key-0006", "NO-SUCH-COUPON", LAMP), "not valid");
        assertConflict(attempt("buyer-elig2", "elig-key-0007", "bad code!", LAMP), "not valid");

        createCoupon("ELIG-CAMPAIGN", Map.of());
        UUID campaignId = UUID.fromString("8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d2001");
        assertConflict(mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-elig2"))
                        .header("Idempotency-Key", "elig-key-0008")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + LAMP
                                + "\",\"quantity\":1}],\"outcome\":\"SUCCESS\",\"campaignId\":\"" + campaignId
                                + "\",\"couponCode\":\"ELIG-CAMPAIGN\"}"))
                .andReturn(), "campaign");

        createCoupon("ELIG-OFF", Map.of());
        mockMvc.perform(patch("/api/v1/orders/coupons/ELIG-OFF").with(operator("op-elig"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/orders/coupons/ELIG-OFF").with(admin("admin-coupon"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms.status").value("DISABLED"));
        assertConflict(attempt("buyer-elig2", "elig-key-0009", "ELIG-OFF", LAMP), "not valid");
        mockMvc.perform(get("/api/v1/orders").with(customer("buyer-elig2")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void couponUsageLimitsCountLiveOrdersOnlyAndSurviveReplays() throws Exception {
        createCoupon("LIMIT-ONE", Map.of("usageLimit", 1, "perCustomerLimit", 1));
        createCoupon("LIMIT-PER", Map.of("perCustomerLimit", 2));

        String first = place("buyer-lim-a", "lim-key-000001", "LIMIT-ONE", LAMP);
        assertThat(couponUsed("LIMIT-ONE")).isEqualTo(1);
        // replaying the same request does not use the coupon twice
        mockMvc.perform(post("/api/v1/orders/checkout").with(customer("buyer-lim-a"))
                        .header("Idempotency-Key", "lim-key-000001").contentType(MediaType.APPLICATION_JSON)
                        .content(cartJson("LIMIT-ONE", LAMP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.total").value(20.00));
        assertThat(couponUsed("LIMIT-ONE")).isEqualTo(1);
        // the same key with another coupon is a different request
        assertThat(attempt("buyer-lim-a", "lim-key-000001", "LIMIT-PER", LAMP).getResponse().getStatus())
                .isEqualTo(409);

        assertConflict(attempt("buyer-lim-b", "lim-key-000002", "LIMIT-ONE", LAMP), "usage limit");

        // per-customer limit
        place("buyer-lim-a", "lim-key-000003", "LIMIT-PER", LAMP);
        place("buyer-lim-a", "lim-key-000004", "LIMIT-PER", LAMP);
        assertConflict(attempt("buyer-lim-a", "lim-key-000005", "LIMIT-PER", LAMP), "already used");
        place("buyer-lim-b", "lim-key-000006", "LIMIT-PER", LAMP);

        // cancelling a not-yet-confirmed order frees the usage
        mockMvc.perform(post("/api/v1/orders/" + first + "/cancel").with(customer("buyer-lim-a")))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(couponUsed("LIMIT-ONE")).isZero();
        String second = place("buyer-lim-b", "lim-key-000007", "LIMIT-ONE", LAMP);
        assertThat(couponUsed("LIMIT-ONE")).isEqualTo(1);

        // an order that fails (no stock) frees it too
        saga.accept(failedReservation(second));
        mockMvc.perform(get("/api/v1/orders/" + second).with(customer("buyer-lim-b")))
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(couponUsed("LIMIT-ONE")).isZero();

        // a confirmed order keeps its usage
        String confirmed = place("buyer-lim-c", "lim-key-000008", "LIMIT-ONE", LAMP);
        confirm(confirmed, LAMP);
        assertThat(couponUsed("LIMIT-ONE")).isEqualTo(1);
        assertConflict(attempt("buyer-lim-d", "lim-key-000009", "LIMIT-ONE", LAMP), "usage limit");
    }

    @Test
    void couponUsageLimitHoldsUnderConcurrentCheckouts() throws Exception {
        createCoupon("RACE-ONE", Map.of("usageLimit", 1));
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int index = 0; index < threads; index++) {
            int number = index;
            results.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return attempt("buyer-race-" + number, "race-key-0000" + number, "RACE-ONE", LAMP)
                        .getResponse().getStatus();
            }));
        }
        start.countDown();
        int created = 0;
        int conflicts = 0;
        for (java.util.concurrent.Future<Integer> result : results) {
            int code = result.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 409) {
                conflicts++;
            }
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(couponUsed("RACE-ONE")).isEqualTo(1);
    }

    @Test
    void commissionIsCalculatedPerSellerAndHistoryIsNeverRewritten() throws Exception {
        mockMvc.perform(post("/api/v1/orders/finance/commission-rates").with(seller("seller-2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sellerId\":\"seller-2\",\"rateBps\":1500}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/orders/finance/commission-rates").with(operator("op-comm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sellerId\":\"seller-2\",\"rateBps\":1500}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/orders/finance/commission-rates").with(admin("admin-comm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sellerId\":\"seller-2\",\"rateBps\":4000}"))
                .andExpect(status().isBadRequest());
        setRate("seller-2", 1500);

        String id = confirmed("buyer-comm", "comm-key-00001", null, LAMP, SHIRT);
        List<Map<String, Object>> entries = ledgerOf(id);
        assertThat(entries).hasSize(6);
        assertThat(amount(entries, "seller-1", "SELLER_PAYABLE", "SALE")).isEqualByComparingTo("25.00");
        assertThat(amount(entries, "seller-1", "SELLER_PAYABLE", "COMMISSION")).isEqualByComparingTo("-2.50");
        assertThat(amount(entries, "seller-1", "PLATFORM_REVENUE", "COMMISSION")).isEqualByComparingTo("2.50");
        assertThat(amount(entries, "seller-2", "SELLER_PAYABLE", "SALE")).isEqualByComparingTo("19.50");
        // 19.50 * 15% = 2.925 -> 2.93 (HALF_UP)
        assertThat(amount(entries, "seller-2", "SELLER_PAYABLE", "COMMISSION")).isEqualByComparingTo("-2.93");
        assertThat(amount(entries, "seller-2", "PLATFORM_REVENUE", "COMMISSION")).isEqualByComparingTo("2.93");
        assertThat(jdbc.queryForObject("""
                SELECT DISTINCT rate_bps FROM ledger_entry
                WHERE order_id = ?::uuid AND seller_id = 'seller-2' AND entry_type = 'COMMISSION'
                """, Integer.class, id)).isEqualTo(1500);

        // a later rate change only affects new orders
        setRate("seller-2", 2000);
        assertThat(ledgerOf(id)).isEqualTo(entries);
        String later = confirmed("buyer-comm", "comm-key-00002", null, SHIRT);
        assertThat(amount(ledgerOf(later), "seller-2", "SELLER_PAYABLE", "COMMISSION")).isEqualByComparingTo("-3.90");
        mockMvc.perform(get("/api/v1/orders/finance/commission-rates").with(operator("op-comm")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultRateBps").value(1000))
                .andExpect(jsonPath("$.rates.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        // platform-funded coupon: commission stays on the gross, the platform carries the discount
        createCoupon("COMM-PLATFORM", Map.of("fixedAmount", 5.00));
        String platform = confirmed("buyer-comm", "comm-key-00003", "COMM-PLATFORM", LAMP, SHIRT);
        List<Map<String, Object>> platformEntries = ledgerOf(platform);
        assertThat(amount(platformEntries, "seller-1", "SELLER_PAYABLE", "COMMISSION")).isEqualByComparingTo("-2.50");
        assertThat(amount(platformEntries, "seller-2", "SELLER_PAYABLE", "COMMISSION")).isEqualByComparingTo("-3.90");
        assertThat(amount(platformEntries, "seller-1", "PLATFORM_REVENUE", "PLATFORM_DISCOUNT"))
                .isEqualByComparingTo("-2.81");
        assertThat(amount(platformEntries, "seller-2", "PLATFORM_REVENUE", "PLATFORM_DISCOUNT"))
                .isEqualByComparingTo("-2.19");
        assertThat(net(platformEntries, "seller-1")).isEqualByComparingTo("22.19");
        assertThat(net(platformEntries, "seller-2")).isEqualByComparingTo("17.31");

        // seller-funded coupon: the seller carries the discount, commission is on the net
        createCoupon("COMM-SELLER", mapOf("discountType", "PERCENT", "percent", 10, "fixedAmount", null,
                "sellerId", "seller-2"));
        String funded = confirmed("buyer-comm", "comm-key-00004", "COMM-SELLER", LAMP, SHIRT);
        List<Map<String, Object>> fundedEntries = ledgerOf(funded);
        assertThat(amount(fundedEntries, "seller-2", "SELLER_PAYABLE", "DISCOUNT")).isEqualByComparingTo("-1.95");
        assertThat(amount(fundedEntries, "seller-2", "SELLER_PAYABLE", "COMMISSION")).isEqualByComparingTo("-3.51");
        assertThat(net(fundedEntries, "seller-2")).isEqualByComparingTo("17.55");
        assertThat(net(fundedEntries, "seller-1")).isEqualByComparingTo("25.00");
        assertThat(reconciliation().balanced()).isTrue();
    }

    @Test
    void refundsAreReversalEntriesAndTheLedgerIsAppendOnly() throws Exception {
        String id = confirmed("buyer-refund", "refund-key-0001", null, LAMP);
        String pending = confirmed("buyer-refund", "refund-key-0002", null, LAMP);

        String body = "{\"orderId\":\"" + id + "\",\"sellerId\":\"seller-1\",\"reason\":\"Customer returned the lamp\"}";
        // not shipped yet: nothing to refund, sellers cancel instead
        mockMvc.perform(post("/api/v1/orders/finance/refunds").with(admin("admin-refund"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertThat(fulfil("seller-1", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(fulfil("seller-1", id, "SHIPPED", "TRACK-RRR1", null).getResponse().getStatus()).isEqualTo(200);

        for (RequestPostProcessor who : List.of(operator("op-refund"), seller("seller-1"), customer("buyer-refund"))) {
            mockMvc.perform(post("/api/v1/orders/finance/refunds").with(who)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/orders/finance/refunds").with(admin("admin-refund"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + id + "\",\"sellerId\":\"seller-1\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/orders/finance/refunds").with(admin("admin-refund"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversedEntries").value(3))
                .andExpect(jsonPath("$.alreadyRefunded").value(false));
        // replay: nothing new is written
        mockMvc.perform(post("/api/v1/orders/finance/refunds").with(admin("admin-refund"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversedEntries").value(0))
                .andExpect(jsonPath("$.alreadyRefunded").value(true));

        List<Map<String, Object>> entries = ledgerOf(id);
        assertThat(entries).hasSize(6);
        assertThat(net(entries, "seller-1")).isEqualByComparingTo("0.00");
        assertThat(entries.stream().filter(entry -> entry.get("reversesEntryId") != null).count()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE order_id = ?::uuid", Integer.class,
                pending)).isEqualTo(3);
        mockMvc.perform(post("/api/v1/orders/finance/refunds").with(admin("admin-refund"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID()
                                + "\",\"sellerId\":\"seller-1\",\"reason\":\"No such group\"}"))
                .andExpect(status().isNotFound());

        // the ledger can never be edited or deleted, not even by the application's own role
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entry SET amount = amount + 1 WHERE order_id = ?::uuid", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entry WHERE order_id = ?::uuid", id))
                .hasMessageContaining("append-only");
        assertThat(reconciliation().balanced()).isTrue();
    }

    @Test
    void settlementIsIdempotentAndCarriesRefundAdjustmentsForward() throws Exception {
        String id = confirmed("buyer-settle", "settle-key-0001", null, LAMP);
        assertThat(fulfil("seller-1", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(fulfil("seller-1", id, "SHIPPED", "TRACK-SSS1", null).getResponse().getStatus()).isEqualTo(200);
        BigDecimal eligibleWhileShipped = mine("seller-1", "eligibleForSettlement");
        assertThat(fulfil("seller-1", id, "DELIVERED", null, null).getResponse().getStatus()).isEqualTo(200);
        BigDecimal eligible = mine("seller-1", "eligibleForSettlement");
        // a delivered group becomes payable: 25.00 - 10% commission
        assertThat(eligible.subtract(eligibleWhileShipped)).isEqualByComparingTo("22.50");
        BigDecimal settledBefore = mine("seller-1", "settled");

        assertThat(settle(null, "seller-1", admin("admin-settle")).getResponse().getStatus()).isEqualTo(400);
        assertThat(settle("settle-run-0001", "seller-1", operator("op-settle")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(settle("settle-run-0001", "seller-1", seller("seller-1")).getResponse().getStatus())
                .isEqualTo(403);

        MvcResult first = settle("settle-run-0001", "seller-1", admin("admin-settle"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String firstJson = first.getResponse().getContentAsString();
        assertThat((Boolean) com.jayway.jsonpath.JsonPath.read(firstJson, "$.replayed")).isFalse();
        assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(firstJson, "$.settlements")).hasSize(1);
        assertThat(dec(com.jayway.jsonpath.JsonPath.read(firstJson, "$.settlements[0].amount")))
                .isEqualByComparingTo(eligible);
        String settlementId = com.jayway.jsonpath.JsonPath.read(firstJson, "$.settlements[0].id");

        // same key again: the original result, nothing new
        String replayJson = settle("settle-run-0001", "seller-1", admin("admin-settle")).getResponse()
                .getContentAsString();
        assertThat((Boolean) com.jayway.jsonpath.JsonPath.read(replayJson, "$.replayed")).isTrue();
        assertThat((String) com.jayway.jsonpath.JsonPath.read(replayJson, "$.settlements[0].id"))
                .isEqualTo(settlementId);
        // a new key finds nothing left to settle
        String otherJson = settle("settle-run-0002", "seller-1", admin("admin-settle")).getResponse()
                .getContentAsString();
        assertThat((Boolean) com.jayway.jsonpath.JsonPath.read(otherJson, "$.replayed")).isFalse();
        assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(otherJson, "$.settlements")).isEmpty();

        assertThat(mine("seller-1", "settled")).isEqualByComparingTo(settledBefore.add(eligible));
        assertThat(mine("seller-1", "eligibleForSettlement")).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT ledger_entry_id FROM settlement_entry
                                      GROUP BY ledger_entry_id HAVING COUNT(*) > 1) duplicated
                """, Integer.class)).isZero();

        // refund after settlement: a negative adjustment that is carried forward, not a payout
        BigDecimal outstanding = mine("seller-1", "outstanding");
        mockMvc.perform(post("/api/v1/orders/finance/refunds").with(admin("admin-settle"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + id + "\",\"sellerId\":\"seller-1\",\"reason\":\"Refund after payout\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversedEntries").value(3));
        assertThat(mine("seller-1", "outstanding")).isEqualByComparingTo(outstanding.subtract(new BigDecimal("22.50")));
        String carryJson = settle("settle-run-0003", "seller-1", admin("admin-settle")).getResponse()
                .getContentAsString();
        assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(carryJson, "$.settlements")).isEmpty();
        mockMvc.perform(get("/api/v1/orders/finance/settlements").with(operator("op-settle")))
                .andExpect(status().isOk());
        assertThat(reconciliation().balanced()).isTrue();
    }

    @Test
    void concurrentSettlementRunsWithOneKeyCreateOneSettlement() throws Exception {
        // two delivered orders (+45.00) outweigh any carried-forward refund adjustment (-22.50)
        for (String key : List.of("settle-key-0002", "settle-key-0003")) {
            String id = confirmed("buyer-settle2", key, null, LAMP);
            assertThat(fulfil("seller-1", id, "PACKED", null, null).getResponse().getStatus()).isEqualTo(200);
            assertThat(fulfil("seller-1", id, "SHIPPED", "TRACK-SSS2", null).getResponse().getStatus()).isEqualTo(200);
            assertThat(fulfil("seller-1", id, "DELIVERED", null, null).getResponse().getStatus()).isEqualTo(200);
        }

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
        for (int index = 0; index < threads; index++) {
            results.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return settle("settle-run-race1", "seller-1", admin("admin-settle")).getResponse()
                        .getContentAsString();
            }));
        }
        start.countDown();
        int original = 0;
        for (java.util.concurrent.Future<String> result : results) {
            String json = result.get(30, TimeUnit.SECONDS);
            if (!(Boolean) com.jayway.jsonpath.JsonPath.read(json, "$.replayed")) {
                original++;
            }
        }
        pool.shutdown();
        assertThat(original).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE starts_with(run_key, 'settle-run-race1:')",
                Integer.class)).isEqualTo(1);
        assertThat(reconciliation().balanced()).isTrue();
    }

    @Test
    void reconciliationFlagsAGroupThatNoLongerMatchesItsLines() throws Exception {
        String id = confirmed("buyer-recon", "recon-key-00001", null, LAMP);
        assertThat(reconciliation().balanced()).isTrue();
        mockMvc.perform(get("/api/v1/orders/finance/reconciliation").with(customer("buyer-recon")))
                .andExpect(status().isForbidden());

        jdbc.update("UPDATE order_item SET line_total = line_total + 1 WHERE order_id = ?::uuid", id);
        try {
            String json = mockMvc.perform(get("/api/v1/orders/finance/reconciliation").with(operator("op-recon")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balanced").value(false))
                    .andReturn().getResponse().getContentAsString();
            assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(json,
                    "$.groupMismatches[?(@.orderId == '" + id + "')]")).hasSize(1);
        } finally {
            jdbc.update("UPDATE order_item SET line_total = line_total - 1 WHERE order_id = ?::uuid", id);
        }
        assertThat(reconciliation().balanced()).isTrue();
    }

    private String place(String customer, String key, String coupon, UUID... products) throws Exception {
        MvcResult result = attempt(customer, key, coupon, products);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private MvcResult attempt(String customer, String key, String coupon, UUID... products) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer(customer))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cartJson(coupon, products)))
                .andReturn();
    }

    private static String cartJson(String coupon, UUID... products) {
        StringBuilder items = new StringBuilder();
        for (UUID product : products) {
            if (items.length() > 0) {
                items.append(',');
            }
            items.append("{\"productId\":\"").append(product).append("\",\"quantity\":1}");
        }
        return "{\"items\":[" + items + "],\"outcome\":\"SUCCESS\""
                + (coupon == null ? "" : ",\"couponCode\":\"" + coupon + "\"") + "}";
    }

    private void confirm(String orderId, UUID... products) throws Exception {
        List<UUID> reservations = new java.util.ArrayList<>();
        for (UUID product : products) {
            UUID reservation = UUID.randomUUID();
            reservations.add(reservation);
            saga.accept(reserved(orderId, product, reservation));
        }
        saga.accept(payment(orderId, "payment.succeeded"));
        for (UUID reservation : reservations) {
            saga.accept(stock(reservation, "inventory.committed"));
        }
        assertThat(jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?::uuid", String.class, orderId))
                .isEqualTo("CONFIRMED");
    }

    private String confirmed(String customer, String key, String coupon, UUID... products) throws Exception {
        String id = place(customer, key, coupon, products);
        confirm(id, products);
        return id;
    }

    private MvcResult fulfil(String sellerSubject, String orderId, String status, String reference, String note)
            throws Exception {
        return fulfil(sellerSubject, orderId, status, reference, note, seller(sellerSubject));
    }

    private MvcResult fulfil(
            String ignored, String orderId, String status, String reference, String note, RequestPostProcessor who)
            throws Exception {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("status", status);
        if (reference != null) {
            request.put("shipmentReference", reference);
        }
        if (note != null) {
            request.put("note", note);
        }
        return mockMvc.perform(patch("/api/v1/orders/" + orderId + "/fulfillment")
                        .with(who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static Map<String, Object> couponBody(String code, Map<String, Object> overrides) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("discountType", "FIXED");
        body.put("fixedAmount", 5.00);
        body.put("currency", "USD");
        body.put("startsAt", Instant.now().minusSeconds(3600).toString());
        body.put("endsAt", Instant.now().plusSeconds(86400).toString());
        body.putAll(overrides);
        return body;
    }

    private int createCouponStatus(String code, Map<String, Object> overrides) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/coupons").with(admin("admin-coupon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(couponBody(code, overrides))))
                .andReturn().getResponse().getStatus();
    }

    private void createCoupon(String code, Map<String, Object> overrides) throws Exception {
        assertThat(createCouponStatus(code, overrides)).as("create coupon " + code).isEqualTo(201);
    }

    private int couponUsed(String code) throws Exception {
        String json = mockMvc.perform(get("/api/v1/orders/coupons").with(operator("op-coupons")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> used = com.jayway.jsonpath.JsonPath.read(json, "$[?(@.terms.code == '" + code + "')].used");
        return used.getFirst();
    }

    private static void assertConflict(MvcResult result, String fragment) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains(fragment);
    }

    private void setRate(String sellerId, int bps) throws Exception {
        mockMvc.perform(post("/api/v1/orders/finance/commission-rates").with(admin("admin-comm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerId\":\"" + sellerId + "\",\"rateBps\":" + bps + "}"))
                .andExpect(status().isCreated());
    }

    private List<Map<String, Object>> ledgerOf(String orderId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/orders/finance/ledger").param("orderId", orderId)
                        .param("size", "100").with(operator("op-ledger")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.items");
    }

    private static BigDecimal dec(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal amount(List<Map<String, Object>> entries, String seller, String account, String type) {
        return entries.stream()
                .filter(entry -> seller.equals(entry.get("sellerId")) && account.equals(entry.get("account"))
                        && type.equals(entry.get("entryType")))
                .map(entry -> dec(entry.get("amount")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " entry on " + account + " for " + seller));
    }

    /** Sum of every entry of one seller's group: always what the customer paid for that seller's lines. */
    private static BigDecimal net(List<Map<String, Object>> entries, String seller) {
        return entries.stream()
                .filter(entry -> seller.equals(entry.get("sellerId")))
                .map(entry -> dec(entry.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal mine(String seller, String field) throws Exception {
        String json = mockMvc.perform(get("/api/v1/orders/finance/me").with(seller(seller)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> values = com.jayway.jsonpath.JsonPath.read(json, "$.balances[?(@.currency == 'USD')]." + field);
        return values.isEmpty() ? BigDecimal.ZERO : dec(values.getFirst());
    }

    private MvcResult settle(String key, String sellerId, RequestPostProcessor who) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/orders/finance/settlements")
                .with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sellerId\":\"" + sellerId + "\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request).andReturn();
    }

    private record Reconciled(boolean balanced) {
    }

    private Reconciled reconciliation() throws Exception {
        String json = mockMvc.perform(get("/api/v1/orders/finance/reconciliation").with(operator("op-recon-all")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Reconciled(com.jayway.jsonpath.JsonPath.read(json, "$.balanced"));
    }

    private static String body(UUID productId, int quantity, String outcome) {
        return "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                + ",\"price\":1}],\"outcome\":\"" + outcome + "\"}";
    }

    private static String body(UUID productId, int quantity, String outcome, UUID campaignId) {
        return "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                + "}],\"outcome\":\"" + outcome + "\",\"campaignId\":\"" + campaignId + "\"}";
    }

    private static RequestPostProcessor customer(String subject) {
        return jwt().jwt(builder -> builder.subject(subject).claim("realm_access", Map.of("roles", List.of("CUSTOMER"))));
    }

    private static RequestPostProcessor seller(String subject) {
        return jwt().jwt(builder -> builder.subject(subject).claim("realm_access", Map.of("roles", List.of("SELLER"))));
    }

    private static RequestPostProcessor admin(String subject) {
        return jwt().jwt(builder -> builder.subject(subject).claim("realm_access", Map.of("roles", List.of("ADMIN"))));
    }

    private static RequestPostProcessor operator(String subject) {
        return jwt().jwt(builder -> builder.subject(subject).claim("realm_access", Map.of("roles", List.of("OPERATOR"))));
    }

    private static HttpServer startDownstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", OrderApplicationTests::handle);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("The checkout stub could not start.", exception);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if ("GET".equals(method) && path.startsWith("/api/v1/inventory/campaigns/")) {
            if ("INVENTORY".equals(fault)) {
                send(exchange, 503, "{\"error\":\"down\"}");
                return;
            }
            String id = path.substring(path.lastIndexOf('/') + 1);
            send(exchange, 200, "{\"id\":\"" + id + "\",\"productId\":\"" + LAMP
                    + "\",\"price\":9.00,\"currency\":\"USD\",\"status\":\"ACTIVE\",\"remaining\":10,\"perCustomerLimit\":2}");
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/v1/products/")) {
            CATALOG_CALLS.incrementAndGet();
            if ("CATALOG".equals(fault)) {
                send(exchange, 500, "{\"error\":\"down\"}");
                return;
            }
            if ("MISSING".equals(fault)) {
                send(exchange, 404, "{\"error\":\"missing\"}");
                return;
            }
            if ("SLOW".equals(fault)) {
                int inFlight = SLOW_IN_FLIGHT.incrementAndGet();
                SLOW_PEAK.accumulateAndGet(inFlight, Math::max);
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    SLOW_IN_FLIGHT.decrementAndGet();
                }
            }
            UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            String name = SHIRT.equals(id) ? "Shirt" : "Desk lamp";
            String sku = SHIRT.equals(id) ? "SHIRT" : "LAMP";
            String price = SHIRT.equals(id) ? "19.50" : "25.00";
            String sellerId = SHIRT.equals(id) ? "seller-2" : "seller-1";
            send(exchange, 200, "{\"id\":\"" + id + "\",\"sellerId\":\"" + sellerId + "\",\"name\":\"" + name
                    + "\",\"sku\":\"" + sku + "\",\"price\":" + price + ",\"currency\":\"USD\",\"status\":\"ACTIVE\"}");
            return;
        }
        if ("POST".equals(method) && "/api/v1/inventory/reservations".equals(path)) {
            RESERVES.incrementAndGet();
            if ("STOCK".equals(fault) && request.contains(SHIRT.toString())) {
                send(exchange, 409, "{\"error\":\"Not enough stock is available.\"}");
                return;
            }
            send(exchange, 201, "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"HELD\"}");
            return;
        }
        if ("POST".equals(method) && path.endsWith("/commit")) {
            COMMITS.incrementAndGet();
            send(exchange, 200, "{\"status\":\"COMMITTED\"}");
            return;
        }
        if ("POST".equals(method) && path.endsWith("/release")) {
            RELEASES.incrementAndGet();
            send(exchange, 200, "{\"status\":\"RELEASED\"}");
            return;
        }
        if ("POST".equals(method) && "/api/v1/payments".equals(path)) {
            if ("PAYMENT".equals(fault)) {
                send(exchange, 503, "{\"error\":\"down\"}");
                return;
            }
            send(exchange, 201, "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
            return;
        }
        if ("POST".equals(method) && path.endsWith("/simulate-success")) {
            send(exchange, 200, "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCEEDED\"}");
            return;
        }
        if ("POST".equals(method) && path.endsWith("/simulate-failure")) {
            send(exchange, 200, "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"FAILED\"}");
            return;
        }
        if ("POST".equals(method) && path.endsWith("/cancel")) {
            send(exchange, 200, "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"CANCELLED\"}");
            return;
        }
        send(exchange, 404, "{\"error\":\"missing\"}");
    }

    private static void send(HttpExchange exchange, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseTestConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:17.11-alpine");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtConfiguration {

        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            RSAPublicKey publicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
            return NimbusJwtDecoder.withPublicKey(publicKey).build();
        }
    }
}

package com.bazaar.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.bazaar.order.checkout.SagaCoordinator;
import com.bazaar.order.messaging.EventEnvelope;
import com.bazaar.order.messaging.OrderEventConsumer;
import com.bazaar.order.outbox.OutboxPublisher;

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
    private JdbcTemplate jdbc;

    @Autowired
    private SagaCoordinator saga;

    @Autowired
    private ObjectMapper objectMapper;

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

    private static String body(UUID productId, int quantity, String outcome) {
        return "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                + ",\"price\":1}],\"outcome\":\"" + outcome + "\"}";
    }

    private static RequestPostProcessor customer(String subject) {
        return jwt().jwt(builder -> builder.subject(subject).claim("realm_access", Map.of("roles", List.of("CUSTOMER"))));
    }

    private static RequestPostProcessor admin(String subject) {
        return jwt().jwt(builder -> builder.subject(subject).claim("realm_access", Map.of("roles", List.of("ADMIN"))));
    }

    private static HttpServer startDownstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
        if ("GET".equals(method) && path.startsWith("/api/v1/products/")) {
            if ("CATALOG".equals(fault)) {
                send(exchange, 500, "{\"error\":\"down\"}");
                return;
            }
            UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            String name = SHIRT.equals(id) ? "Shirt" : "Desk lamp";
            String sku = SHIRT.equals(id) ? "SHIRT" : "LAMP";
            String price = SHIRT.equals(id) ? "19.50" : "25.00";
            send(exchange, 200, "{\"id\":\"" + id + "\",\"sellerId\":\"seller-1\",\"name\":\"" + name
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

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

import com.bazaar.order.messaging.OrderEventConsumer;
import com.bazaar.order.outbox.OutboxPublisher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@SpringBootTest(properties = {"bazaar.security.jwt.remote=false", "bazaar.outbox.poll-delay=3600000"})
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
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND status = 'PENDING'",
                Integer.class,
                id);
        assertThat(pending).isEqualTo(2);
        outbox.publishPending();
        waitUntilProcessed(id, 2);
        jdbc.update("UPDATE outbox SET status = 'PENDING', claimed_at = NULL, published_at = NULL WHERE aggregate_id = ?", id);
        outbox.publishPending();
        Thread.sleep(1000);
        assertThat(processed(id)).isEqualTo(2);
    }

    @Test
    void rejectedCheckoutWritesNoOutboxRow() throws Exception {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
        fault = "STOCK";
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-outbox-stock"))
                        .header("Idempotency-Key", "order-key-outbox-stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SHIRT, 1, "SUCCESS")))
                .andExpect(status().isConflict());
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
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.total").value(50.00))
                .andExpect(jsonPath("$.items[0].unitPrice").value(25.00))
                .andExpect(jsonPath("$.items[0].name").value("Desk lamp"))
                .andExpect(jsonPath("$.history[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.history[1].status").value("CONFIRMED"))
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
                .andExpect(status().isConflict());

        assertThat(RESERVES.get()).isEqualTo(1);
        assertThat(COMMITS.get()).isEqualTo(1);
        assertThat(RELEASES.get()).isZero();
    }

    @Test
    void failedPaymentReleasesStock() throws Exception {
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-fail"))
                        .header("Idempotency-Key", "order-key-fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "FAILURE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.total").value(25.00));

        assertThat(RESERVES.get()).isEqualTo(1);
        assertThat(COMMITS.get()).isZero();
        assertThat(RELEASES.get()).isEqualTo(1);
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
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Not enough stock is available. Use a new Idempotency-Key to try again."));

        mockMvc.perform(get("/api/v1/orders").with(customer("buyer-stock")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(RELEASES.get()).isEqualTo(1);
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
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void paymentOutageLeavesAnOrderThatCanBeCancelled() throws Exception {
        fault = "PAYMENT";
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(customer("buyer-hold"))
                        .header("Idempotency-Key", "order-key-hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LAMP, 1, "SUCCESS")))
                .andExpect(status().isServiceUnavailable());
        assertThat(RELEASES.get()).isZero();

        MvcResult listed = mockMvc.perform(get("/api/v1/orders").with(customer("buyer-hold")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING_PAYMENT"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(listed.getResponse().getContentAsString(), "$[0].id");

        mockMvc.perform(post("/api/v1/orders/" + id + "/cancel").with(customer("buyer-hold")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(RELEASES.get()).isEqualTo(1);
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

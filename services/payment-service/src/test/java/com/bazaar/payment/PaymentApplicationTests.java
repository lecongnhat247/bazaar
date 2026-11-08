package com.bazaar.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.bazaar.payment.messaging.CommandConsumer;
import com.bazaar.payment.messaging.ServiceEventConsumer;
import com.bazaar.payment.outbox.OutboxPublisher;

@SpringBootTest(properties = {"bazaar.security.jwt.remote=false", "bazaar.outbox.poll-delay=3600000"})
@AutoConfigureMockMvc
@Import({PaymentApplicationTests.DatabaseTestConfiguration.class, PaymentApplicationTests.TestJwtConfiguration.class})
class PaymentApplicationTests {

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
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private ServiceEventConsumer events;

    @Autowired
    private CommandConsumer commands;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    @Test
    void applicationContextStarts() {
        assertThat(applicationContext).isNotNull();
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
        mockMvc.perform(get("/api/v1/payments/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("payment-service"))
                .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unknownApiRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/payments/private"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void successIsIdempotentAndBlocksASecondCharge() throws Exception {
        UUID orderId = UUID.randomUUID();
        MvcResult created = mockMvc.perform(post("/api/v1/payments")
                        .with(customer("payer-a"))
                        .header("Idempotency-Key", "pay-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, "25.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(25.00))
                .andExpect(jsonPath("$.attempts[0].type").value("CREATE"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/v1/payments")
                        .with(customer("payer-a"))
                        .header("Idempotency-Key", "pay-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, "25.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        mockMvc.perform(post("/api/v1/payments")
                        .with(customer("payer-a"))
                        .header("Idempotency-Key", "pay-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, "10.00")))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/payments/" + id).with(customer("payer-other")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/payments/" + id + "/simulate-success").with(customer("payer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.attempts[1].type").value("SIMULATE_SUCCESS"));

        mockMvc.perform(post("/api/v1/payments/" + id + "/simulate-success").with(customer("payer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.attempts.length()").value(2));

        mockMvc.perform(post("/api/v1/payments/" + id + "/simulate-failure").with(customer("payer-a")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/payments")
                        .with(customer("payer-a"))
                        .header("Idempotency-Key", "pay-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, "25.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This order already has a payment."));
    }

    @Test
    void failureAllowsAnotherPaymentForTheSameOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        MvcResult created = mockMvc.perform(post("/api/v1/payments")
                        .with(customer("payer-fail"))
                        .header("Idempotency-Key", "fail-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, "8.50")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/v1/payments/" + id + "/simulate-failure").with(customer("payer-fail")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(post("/api/v1/payments/" + id + "/simulate-failure").with(customer("payer-fail")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(post("/api/v1/payments/" + id + "/simulate-success").with(customer("payer-fail")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/payments")
                        .with(customer("payer-fail"))
                        .header("Idempotency-Key", "fail-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, "8.50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void cancelStopsThePayment() throws Exception {
        UUID orderId = UUID.randomUUID();
        MvcResult created = mockMvc.perform(post("/api/v1/payments")
                        .with(customer("payer-cancel"))
                        .header("Idempotency-Key", "cancel-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, "3.00")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/v1/payments/" + id + "/cancel").with(customer("payer-cancel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/v1/payments/" + id + "/simulate-success").with(customer("payer-cancel")))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidAmountIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .with(customer("payer-bad"))
                        .header("Idempotency-Key", "bad-amount-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("amount is invalid"));
    }

    @Test
    void duplicateDeliveryIsAppliedOnce() {
        UUID eventId = UUID.randomUUID();
        String aggregateId = UUID.randomUUID().toString();
        String body = """
                {"eventId":"%s","eventType":"payment.succeeded","eventVersion":1,"aggregateId":"%s","aggregateType":"payment","occurredAt":"2026-11-07T00:00:00Z","correlationId":"22222222-2222-4222-8222-222222222222","producer":"payment-service"}
                """.formatted(eventId, aggregateId);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("bazaar.payment.events", 0, 0L, aggregateId, body);
        events.onEvent(record);
        events.onEvent(record);
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class,
                eventId);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void successfulPaymentIsPublishedOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        MvcResult created = mockMvc.perform(post("/api/v1/payments")
                        .with(customer("payer-outbox"))
                        .header("Idempotency-Key", "pay-outbox-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, "12.00")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertThat(rows(id)).isZero();

        mockMvc.perform(post("/api/v1/payments/" + id + "/simulate-success").with(customer("payer-outbox")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/payments/" + id + "/simulate-success").with(customer("payer-outbox")))
                .andExpect(status().isOk());
        assertThat(rows(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT event_type FROM outbox WHERE aggregate_id = ?",
                String.class,
                id)).isEqualTo("payment.succeeded");
        outbox.publishPending();
        waitUntilProcessed(id, 1);
        jdbc.update("UPDATE outbox SET status = 'PENDING', claimed_at = NULL, published_at = NULL WHERE aggregate_id = ?", id);
        outbox.publishPending();
        Thread.sleep(1000);
        assertThat(processed(id)).isEqualTo(1);
    }

    private int rows(String aggregateId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ?",
                Integer.class,
                aggregateId);
        return count == null ? 0 : count;
    }

    private void waitUntilProcessed(String aggregateId, int expected) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (processed(aggregateId) == expected) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(processed(aggregateId)).isEqualTo(expected);
    }

    private int processed(String aggregateId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE event_id IN (SELECT event_id FROM outbox WHERE aggregate_id = ?)
                """, Integer.class, aggregateId);
        return count == null ? 0 : count;
    }

    @Test
    void paymentCommandSucceedsOnce() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"payment.requested","eventVersion":1,"aggregateId":"%s","aggregateType":"command","occurredAt":"2026-11-08T00:00:00Z","correlationId":"%s","producer":"order-service","payload":{"orderId":"%s","customerId":"payer-command","amount":"10.00","currency":"USD","outcome":"SUCCESS"}}
                """.formatted(eventId, orderId, orderId, orderId);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("bazaar.payment.commands", 0, 0L, orderId.toString(), body);
        commands.onCommand(record);
        commands.onCommand(record);
        Integer payments = jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, orderId);
        String status = jdbc.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, orderId);
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE event_type = 'payment.succeeded' AND correlation_id IN (SELECT id FROM payment WHERE order_id = ?)",
                Integer.class,
                orderId);
        assertThat(payments).isEqualTo(1);
        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(events).isEqualTo(1);
    }

    private static String body(UUID orderId, String amount) {
        return "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + ",\"currency\":\"USD\"}";
    }

    private static RequestPostProcessor customer(String subject) {
        return jwt().jwt(builder -> builder.subject(subject).claim("realm_access", Map.of("roles", List.of("CUSTOMER"))));
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

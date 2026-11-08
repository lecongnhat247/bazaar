package com.bazaar.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.bazaar.catalog.messaging.ServiceEventConsumer;
import com.bazaar.catalog.outbox.OutboxPublisher;

@SpringBootTest(properties = {"bazaar.security.jwt.remote=false", "bazaar.outbox.poll-delay=3600000"})
@AutoConfigureMockMvc
@Import({CatalogApplicationTests.DatabaseTestConfiguration.class, CatalogApplicationTests.TestJwtConfiguration.class})
class CatalogApplicationTests {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.3-alpine")
            .withExposedPorts(6379);

    static {
        REDIS.start();
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
    private tools.jackson.databind.ObjectMapper objectMapper;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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
        mockMvc.perform(get("/api/v1/catalog/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("catalog-service"))
                .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unknownApiRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/private"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/products").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousSeesActiveProductsOnly() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3));

        mockMvc.perform(get("/api/v1/products/8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1003"))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoriesAreSeeded() throws Exception {
        mockMvc.perform(get("/api/v1/categories").with(customer("customer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].name").value("Electronics"));
    }

    @Test
    void customerSeesActiveProductsAndNotDrafts() throws Exception {
        mockMvc.perform(get("/api/v1/products").with(customer("customer-2")).param("q", "Desk lamp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].price").value(25.00));

        mockMvc.perform(get("/api/v1/products").with(customer("customer-2")).param("q", "Pocket radio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));

        mockMvc.perform(get("/api/v1/products/8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1003").with(customer("customer-2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void sellerOwnsTheProductEvenWhenTheBodyNamesSomeoneElse() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/products")
                        .with(seller("seller-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-b", "SKU-OWNER-1", "12.50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellerId").value("seller-a"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(put("/api/v1/products/" + id)
                        .with(seller("seller-b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-b", "SKU-OWNER-1", "12.50")))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/products/" + id)
                        .with(seller("seller-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-a", "SKU-OWNER-1B", "15.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-OWNER-1B"))
                .andExpect(jsonPath("$.sellerId").value("seller-a"));
    }

    @Test
    void customerCannotCreateOrPublish() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .with(customer("customer-3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("customer-3", "SKU-CUST-1", "10.00")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPriceIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .with(seller("seller-price"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-price", "SKU-PRICE-1", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("price is invalid"));
    }

    @Test
    void duplicateSkuIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .with(seller("seller-sku"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-sku", "SKU-DUP-1", "10.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/products")
                        .with(seller("seller-sku"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-sku", "SKU-DUP-1", "11.00")))
                .andExpect(status().isConflict());
    }

    @Test
    void searchFiltersByKeywordCategoryAndPrice() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .with(customer("customer-4"))
                        .param("q", "shirt")
                        .param("categoryId", "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0003")
                        .param("minPrice", "10")
                        .param("maxPrice", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("FASHION-SHIRT-1"));

        mockMvc.perform(get("/api/v1/products")
                        .with(customer("customer-4"))
                        .param("q", "shirt")
                        .param("minPrice", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void archivedProductCannotReturnToActive() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/products")
                        .with(seller("seller-status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-status", "SKU-STATUS-1", "10.00")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(patch("/api/v1/products/" + id + "/status")
                        .with(seller("seller-status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(patch("/api/v1/products/" + id + "/status")
                        .with(seller("seller-status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/products/" + id + "/status")
                        .with(seller("seller-status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void duplicateDeliveryIsAppliedOnce() {
        UUID eventId = UUID.randomUUID();
        String aggregateId = UUID.randomUUID().toString();
        String body = """
                {"eventId":"%s","eventType":"catalog.product-published","eventVersion":1,"aggregateId":"%s","aggregateType":"product","occurredAt":"2026-11-07T00:00:00Z","correlationId":"22222222-2222-4222-8222-222222222222","producer":"catalog-service"}
                """.formatted(eventId, aggregateId);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("bazaar.catalog.events", 0, 0L, aggregateId, body);
        events.onEvent(record);
        events.onEvent(record);
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class,
                eventId);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void onlyVisibleProductChangesArePublished() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/products")
                        .with(seller("seller-outbox"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-outbox", "SKU-OUTBOX-1", "18.00")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertThat(rows(id)).isZero();

        mockMvc.perform(patch("/api/v1/products/" + id + "/status")
                        .with(seller("seller-outbox"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        assertThat(types(id)).containsExactly("catalog.product-published");

        mockMvc.perform(put("/api/v1/products/" + id)
                        .with(seller("seller-outbox"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-outbox", "SKU-OUTBOX-1", "19.00")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/products/" + id + "/status")
                        .with(seller("seller-outbox"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/products/" + id + "/status")
                        .with(seller("seller-outbox"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());
        assertThat(types(id)).containsExactlyInAnyOrder(
                "catalog.product-published",
                "catalog.product-updated",
                "catalog.product-unpublished");

        outbox.publishPending();
        waitUntilProcessed(id, 3);
        jdbc.update("UPDATE outbox SET status = 'PENDING', claimed_at = NULL, published_at = NULL WHERE aggregate_id = ?", id);
        outbox.publishPending();
        Thread.sleep(1000);
        assertThat(processed(id)).isEqualTo(3);
    }

    @Test
    void publicCatalogReadsUseCacheUntilTheProductEventArrives() throws Exception {
        String lamp = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001";
        UUID lampId = UUID.fromString(lamp);
        try {
            mockMvc.perform(get("/api/v1/products/" + lamp))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Desk lamp"));
            mockMvc.perform(get("/api/v1/products"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(2));

            jdbc.update("UPDATE product SET name = ? WHERE id = ?", "Cached lamp", lampId);

            mockMvc.perform(get("/api/v1/products/" + lamp))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Desk lamp"));
            String listing = mockMvc.perform(get("/api/v1/products"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(listing).contains("Desk lamp").doesNotContain("Cached lamp");
            mockMvc.perform(get("/api/v1/products/" + lamp).header("Bazaar-Read-Source", "database"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Cached lamp"));
            mockMvc.perform(get("/api/v1/products").param("q", "Cached"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(1));

            events.onEvent(productEvent(lamp, "catalog.product-updated"));
            mockMvc.perform(get("/api/v1/products/" + lamp))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Cached lamp"));
            mockMvc.perform(get("/api/v1/products").param("q", "no-such-search-term"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(0));
        } finally {
            jdbc.update("UPDATE product SET name = ? WHERE id = ?", "Desk lamp", lampId);
            events.onEvent(productEvent(lamp, "catalog.product-updated"));
        }
    }

    @Test
    void catalogCacheMissesWhenRedisIsDown() {
        try (GenericContainer<?> down = new GenericContainer<>("redis:8.10.3-alpine").withExposedPorts(6379)) {
            down.start();
            var client = org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                    .commandTimeout(java.time.Duration.ofMillis(200))
                    .build();
            var standalone = new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                    down.getHost(), down.getMappedPort(6379));
            var factory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(standalone, client);
            factory.afterPropertiesSet();
            var template = new org.springframework.data.redis.core.StringRedisTemplate(factory);
            template.afterPropertiesSet();
            var isolated = new com.bazaar.catalog.cache.CatalogCache(
                    template, objectMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), java.time.Duration.ofSeconds(30));
            down.stop();
            assertThat(isolated.product(UUID.randomUUID())).isNull();
            factory.destroy();
        }
    }

    private static ConsumerRecord<String, String> productEvent(String productId, String eventType) {
        String eventId = UUID.randomUUID().toString();
        String body = """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"aggregateId":"%s","aggregateType":"catalog","occurredAt":"2026-11-08T00:00:00Z","correlationId":"%s","producer":"catalog-service"}
                """.formatted(eventId, eventType, productId, productId);
        return new ConsumerRecord<>("bazaar.catalog.events", 0, 0L, productId, body);
    }

    private int rows(String aggregateId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ?",
                Integer.class,
                aggregateId);
        return count == null ? 0 : count;
    }

    private java.util.List<String> types(String aggregateId) {
        return jdbc.queryForList(
                "SELECT event_type FROM outbox WHERE aggregate_id = ? ORDER BY occurred_at, event_type",
                String.class,
                aggregateId);
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

    private static String productJson(String sellerId, String sku, String price) {
        return """
                {"sellerId":"%s","categoryId":"8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0002","name":"Sample %s","description":"A sample","sku":"%s","price":%s,"currency":"USD"}
                """.formatted(sellerId, sku, sku, price);
    }

    private static RequestPostProcessor customer(String subject) {
        return token(subject, "CUSTOMER");
    }

    private static RequestPostProcessor seller(String subject) {
        return token(subject, "SELLER");
    }

    private static RequestPostProcessor token(String subject, String role) {
        return jwt().jwt(builder -> builder
                .subject(subject)
                .claim("realm_access", Map.of("roles", List.of(role))));
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

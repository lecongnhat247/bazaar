package com.bazaar.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "bazaar.security.jwt.remote=false")
@AutoConfigureMockMvc
@Import({InventoryApplicationTests.DatabaseTestConfiguration.class, InventoryApplicationTests.TestJwtConfiguration.class})
class InventoryApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.bazaar.inventory.stock.InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

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
        mockMvc.perform(get("/api/v1/inventory/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("inventory-service"))
                .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unknownApiRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/private"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void seededLampHasTenAvailable() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/products/8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001").with(customer("customer-seed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(10))
                .andExpect(jsonPath("$.reserved").value(0));
    }

    @Test
    void reservationIsIdempotentAndCommitRemovesTheHold() throws Exception {
        UUID productId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/inventory/products/" + productId)
                        .with(seller("seller-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onHand\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(5));

        mockMvc.perform(put("/api/v1/inventory/products/" + productId)
                        .with(customer("customer-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onHand\":9}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/inventory/products/" + productId)
                        .with(seller("seller-b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onHand\":9}"))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(customer("customer-a"))
                        .header("Idempotency-Key", "reserve-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(customer("customer-a"))
                        .header("Idempotency-Key", "reserve-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        mockMvc.perform(get("/api/v1/inventory/products/" + productId).with(customer("customer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(3))
                .andExpect(jsonPath("$.reserved").value(2));

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(customer("customer-a"))
                        .header("Idempotency-Key", "reserve-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":1}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/inventory/reservations/" + id + "/commit").with(customer("customer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        mockMvc.perform(post("/api/v1/inventory/reservations/" + id + "/commit").with(customer("customer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        mockMvc.perform(get("/api/v1/inventory/products/" + productId).with(customer("customer-a")))
                .andExpect(jsonPath("$.available").value(3))
                .andExpect(jsonPath("$.reserved").value(0));

        mockMvc.perform(post("/api/v1/inventory/reservations/" + id + "/release").with(customer("customer-a")))
                .andExpect(status().isConflict());
    }

    @Test
    void releaseReturnsStockAndCanBeRepeated() throws Exception {
        UUID productId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/inventory/products/" + productId)
                        .with(seller("seller-release"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onHand\":4}"))
                .andExpect(status().isOk());
        MvcResult created = mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(customer("customer-release"))
                        .header("Idempotency-Key", "release-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/v1/inventory/reservations/" + id + "/release").with(customer("customer-other")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/inventory/reservations/" + id + "/release").with(customer("customer-release")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mockMvc.perform(post("/api/v1/inventory/reservations/" + id + "/release").with(customer("customer-release")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        mockMvc.perform(get("/api/v1/inventory/products/" + productId).with(customer("customer-release")))
                .andExpect(jsonPath("$.available").value(4))
                .andExpect(jsonPath("$.reserved").value(0));
    }

    @Test
    void expiredReservationReturnsStockAndCannotBeCommitted() throws Exception {
        UUID productId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/inventory/products/" + productId)
                        .with(seller("seller-expire"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onHand\":4}"))
                .andExpect(status().isOk());
        MvcResult created = mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(customer("customer-expire"))
                        .header("Idempotency-Key", "expire-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":3}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        jdbc.update("UPDATE stock_reservation SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), UUID.fromString(id));

        assertThat(inventory.expireDue()).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/v1/inventory/products/" + productId).with(customer("customer-expire")))
                .andExpect(jsonPath("$.available").value(4))
                .andExpect(jsonPath("$.reserved").value(0));
        mockMvc.perform(post("/api/v1/inventory/reservations/" + id + "/commit").with(customer("customer-expire")))
                .andExpect(status().isConflict());
    }

    @Test
    void notEnoughStockIsRejected() throws Exception {
        UUID productId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/inventory/products/" + productId)
                        .with(seller("seller-short"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onHand\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(customer("customer-short"))
                        .header("Idempotency-Key", "short-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Not enough stock is available."));
        mockMvc.perform(get("/api/v1/inventory/products/" + productId).with(customer("customer-short")))
                .andExpect(jsonPath("$.available").value(1));
    }

    @Test
    void concurrentReservationsCannotOversell() throws Exception {
        UUID productId = UUID.randomUUID();
        Jwt seller = token("seller-race", "SELLER");
        inventory.setOnHand(seller, productId, 5);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            int n = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    inventory.reserve(token("customer-race-" + n, "CUSTOMER"), "race-key-" + n, productId, 1);
                    reserved.incrementAndGet();
                } catch (ResponseStatusException conflict) {
                    rejected.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        var stock = inventory.get(token("customer-race-0", "CUSTOMER"), productId);
        assertThat(reserved.get()).isEqualTo(5);
        assertThat(rejected.get()).isEqualTo(3);
        assertThat(stock.available()).isZero();
        assertThat(stock.reserved()).isEqualTo(5);
    }

    private static RequestPostProcessor customer(String subject) {
        return jwt().jwt(builder -> builder.subject(subject).claim("realm_access", Map.of("roles", List.of("CUSTOMER"))));
    }

    private static RequestPostProcessor seller(String subject) {
        return jwt().jwt(builder -> builder.subject(subject).claim("realm_access", Map.of("roles", List.of("SELLER"))));
    }

    private static Jwt token(String subject, String role) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
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

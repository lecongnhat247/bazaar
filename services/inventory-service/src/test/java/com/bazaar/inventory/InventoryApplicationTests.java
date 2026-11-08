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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.bazaar.inventory.messaging.CommandConsumer;
import com.bazaar.inventory.messaging.ServiceEventConsumer;
import com.bazaar.inventory.outbox.OutboxPublisher;
import com.bazaar.inventory.stock.InventoryTransactions.Requested;
import com.bazaar.inventory.stock.StockShortException;

@SpringBootTest(properties = {"bazaar.security.jwt.remote=false", "bazaar.outbox.poll-delay=3600000"})
@AutoConfigureMockMvc
@Import({InventoryApplicationTests.DatabaseTestConfiguration.class, InventoryApplicationTests.TestJwtConfiguration.class})
class InventoryApplicationTests {

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
    private com.bazaar.inventory.stock.InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private com.bazaar.inventory.stock.InventoryTransactions transactions;

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

    @AfterEach
    void drainOutbox() {
        for (int attempt = 0; attempt < 40; attempt++) {
            Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'", Integer.class);
            if (pending == null || pending == 0) {
                return;
            }
            outbox.publishPending();
        }
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

    @Test
    void duplicateReservationKeyTakesStockOnce() throws Exception {
        UUID productId = UUID.randomUUID();
        Jwt customer = token("customer-same-key", "CUSTOMER");
        inventory.setOnHand(token("seller-same-key", "SELLER"), productId, 5);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    inventory.reserve(customer, "same-reservation-key", productId, 1);
                    accepted.incrementAndGet();
                } catch (ResponseStatusException | InterruptedException ignored) {
                    if (ignored instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_reservation WHERE idempotency_key = ?",
                Integer.class,
                "same-reservation-key");
        var stock = inventory.get(customer, productId);
        assertThat(accepted.get()).isEqualTo(threads);
        assertThat(rows).isEqualTo(1);
        assertThat(stock.available()).isEqualTo(4);
        assertThat(stock.reserved()).isEqualTo(1);
    }

    @Test
    void commitAndReleaseCannotBothWin() throws Exception {
        UUID productId = UUID.randomUUID();
        Jwt customer = token("customer-finish", "CUSTOMER");
        inventory.setOnHand(token("seller-finish", "SELLER"), productId, 5);
        var reserved = inventory.reserve(customer, "finish-reservation-key", productId, 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        pool.submit(() -> finish(() -> inventory.commit(customer, reserved.reservation().id()), ready, start, done, won, lost));
        pool.submit(() -> finish(() -> inventory.release(customer, reserved.reservation().id()), ready, start, done, won, lost));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        var stock = inventory.get(customer, productId);
        String status = jdbc.queryForObject(
                "SELECT status FROM stock_reservation WHERE id = ?",
                String.class,
                reserved.reservation().id());
        assertThat(won.get()).isEqualTo(1);
        assertThat(lost.get()).isEqualTo(1);
        assertThat(stock.available()).isGreaterThanOrEqualTo(0);
        assertThat(stock.reserved()).isGreaterThanOrEqualTo(0);
        if ("COMMITTED".equals(status)) {
            assertThat(stock.available()).isEqualTo(4);
            assertThat(stock.reserved()).isZero();
        } else {
            assertThat(status).isEqualTo("RELEASED");
            assertThat(stock.available()).isEqualTo(5);
            assertThat(stock.reserved()).isZero();
        }
    }

    @Test
    void oppositeProductOrderDoesNotDeadlock() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        if (first.compareTo(second) > 0) {
            UUID swap = first;
            first = second;
            second = swap;
        }
        Jwt seller = token("seller-order", "SELLER");
        inventory.setOnHand(seller, first, 2);
        inventory.setOnHand(seller, second, 2);
        Instant now = Instant.now();
        UUID low = first;
        UUID high = second;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> left = new AtomicReference<>();
        AtomicReference<Throwable> right = new AtomicReference<>();
        pool.submit(() -> runReserve(ready, start, done, left, () -> transactions.reserveForSaga(
                UUID.randomUUID(),
                "customer-order",
                List.of(new com.bazaar.inventory.stock.InventoryTransactions.Requested(low, 1),
                        new com.bazaar.inventory.stock.InventoryTransactions.Requested(high, 1)),
                now,
                now.plusSeconds(900))));
        pool.submit(() -> runReserve(ready, start, done, right, () -> transactions.reserveForSaga(
                UUID.randomUUID(),
                "customer-order",
                List.of(new com.bazaar.inventory.stock.InventoryTransactions.Requested(high, 1),
                        new com.bazaar.inventory.stock.InventoryTransactions.Requested(low, 1)),
                now,
                now.plusSeconds(900))));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(left.get()).isNull();
        assertThat(right.get()).isNull();
        var lowStock = inventory.get(token("customer-order", "CUSTOMER"), low);
        var highStock = inventory.get(token("customer-order", "CUSTOMER"), high);
        assertThat(lowStock.available()).isZero();
        assertThat(lowStock.reserved()).isEqualTo(2);
        assertThat(highStock.available()).isZero();
        assertThat(highStock.reserved()).isEqualTo(2);
    }

    @Test
    void unsortedRowLocksAbortOneTransaction() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO inventory_item (product_id, seller_id, available, reserved, updated_at)
                VALUES (?, 'seller-lock', 5, 0, ?)
                """, first, now);
        jdbc.update("""
                INSERT INTO inventory_item (product_id, seller_id, available, reserved, updated_at)
                VALUES (?, 'seller-lock', 5, 0, ?)
                """, second, now);
        CountDownLatch locked = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> left = new AtomicReference<>();
        AtomicReference<Throwable> right = new AtomicReference<>();
        Thread lowThenHigh = new Thread(() -> lockPair(first, second, locked, release, left));
        Thread highThenLow = new Thread(() -> lockPair(second, first, locked, release, right));
        lowThenHigh.start();
        highThenLow.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        lowThenHigh.join(15000);
        highThenLow.join(15000);
        long aborted = java.util.stream.Stream.of(left.get(), right.get()).filter(this::deadlock).count();
        long committed = java.util.stream.Stream.of(left.get(), right.get()).filter(error -> error == null).count();
        assertThat(aborted).isEqualTo(1);
        assertThat(committed).isEqualTo(1);
        Integer negative = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_item
                WHERE product_id IN (?, ?) AND (available < 0 OR reserved < 0)
                """, Integer.class, first, second);
        assertThat(negative).isZero();
    }

    private void finish(
            Runnable action,
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch done,
            AtomicInteger won,
            AtomicInteger lost) {
        ready.countDown();
        try {
            start.await(10, TimeUnit.SECONDS);
            action.run();
            won.incrementAndGet();
        } catch (ResponseStatusException conflict) {
            lost.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
    }

    private void runReserve(
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch done,
            AtomicReference<Throwable> error,
            Runnable action) {
        ready.countDown();
        try {
            start.await(10, TimeUnit.SECONDS);
            action.run();
        } catch (Throwable failure) {
            error.set(failure);
        } finally {
            done.countDown();
        }
    }

    private void lockPair(UUID first, UUID second, CountDownLatch locked, CountDownLatch release, AtomicReference<Throwable> error) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            touch(connection, first);
            locked.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                connection.rollback();
                return;
            }
            touch(connection, second);
            connection.commit();
        } catch (Exception failure) {
            error.set(failure);
        }
    }

    private static void touch(Connection connection, UUID productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE inventory_item SET updated_at = ? WHERE product_id = ?")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setObject(2, productId);
            statement.executeUpdate();
        }
    }

    private boolean deadlock(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && "40P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void duplicateDeliveryIsAppliedOnce() {
        UUID eventId = UUID.randomUUID();
        String aggregateId = UUID.randomUUID().toString();
        String body = """
                {"eventId":"%s","eventType":"inventory.reserved","eventVersion":1,"aggregateId":"%s","aggregateType":"reservation","occurredAt":"2026-11-07T00:00:00Z","correlationId":"22222222-2222-4222-8222-222222222222","producer":"inventory-service"}
                """.formatted(eventId, aggregateId);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("bazaar.inventory.events", 0, 0L, aggregateId, body);
        events.onEvent(record);
        events.onEvent(record);
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class,
                eventId);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void reservationIsPublishedOnceWhenTheOutboxRowIsRetried() throws Exception {
        UUID productId = UUID.randomUUID();
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
        mockMvc.perform(put("/api/v1/inventory/products/" + productId)
                        .with(seller("seller-outbox"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onHand\":4}"))
                .andExpect(status().isOk());
        Integer afterStock = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
        assertThat(afterStock).isEqualTo(before);

        MvcResult created = mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(customer("customer-outbox"))
                        .header("Idempotency-Key", "reserve-outbox-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(customer("customer-outbox"))
                        .header("Idempotency-Key", "reserve-outbox-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":1}"))
                .andExpect(status().isOk());
        assertThat(pending(id)).isEqualTo(1);
        outbox.publishPending();
        waitUntilProcessed(id, 1);
        jdbc.update("UPDATE outbox SET status = 'PENDING', claimed_at = NULL, published_at = NULL WHERE aggregate_id = ?", id);
        outbox.publishPending();
        Thread.sleep(1000);
        assertThat(processed(id)).isEqualTo(1);
    }

    private int pending(String aggregateId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND status = 'PENDING'",
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
    void reservationCommandTakesStockOnceAndReportsAShortage() throws Exception {
        UUID productId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/inventory/products/" + productId)
                        .with(seller("seller-command"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onHand\":1}"))
                .andExpect(status().isOk());
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String body = command(eventId, orderId, productId, 1);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("bazaar.inventory.commands", 0, 0L, orderId.toString(), body);
        commands.onCommand(record);
        commands.onCommand(record);
        mockMvc.perform(get("/api/v1/inventory/products/" + productId).with(customer("customer-command")))
                .andExpect(jsonPath("$.available").value(0))
                .andExpect(jsonPath("$.reserved").value(1));
        Integer reserved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE event_type = 'inventory.reserved' AND correlation_id = ?",
                Integer.class,
                orderId);
        assertThat(reserved).isEqualTo(1);

        UUID failedOrder = UUID.randomUUID();
        commands.onCommand(new ConsumerRecord<>("bazaar.inventory.commands", 0, 1L, failedOrder.toString(),
                command(UUID.randomUUID(), failedOrder, productId, 1)));
        Integer failures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE event_type = 'inventory.reservation-failed' AND aggregate_id = ?",
                Integer.class,
                failedOrder.toString());
        assertThat(failures).isEqualTo(1);
        mockMvc.perform(get("/api/v1/inventory/products/" + productId).with(customer("customer-command")))
                .andExpect(jsonPath("$.available").value(0));
    }

    @Test
    void concurrentCampaignReservationsNeverExceedQuota() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID campaignId = campaign("seller-quota", productId, 40, 15, 15);
        int attempts = 30;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Instant now = Instant.now();
        for (int i = 0; i < attempts; i++) {
            String customerId = "buyer-quota-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    transactions.reserveForSaga(
                            UUID.randomUUID(),
                            customerId,
                            List.of(new Requested(productId, 1)),
                            now,
                            now.plusSeconds(900),
                            campaignId);
                    won.incrementAndGet();
                } catch (StockShortException exception) {
                    lost.incrementAndGet();
                } catch (Throwable failure) {
                    unexpected.set(failure);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(unexpected.get()).isNull();
        assertThat(won.get()).isEqualTo(15);
        assertThat(lost.get()).isEqualTo(15);
        assertCampaign(campaignId, 15, 0, 15, 0);
        var stock = inventory.get(token("customer-quota", "CUSTOMER"), productId);
        assertThat(stock.available()).isEqualTo(25);
        assertThat(stock.reserved()).isEqualTo(15);
    }

    @Test
    void oneCustomerCannotPassTheCampaignLimit() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID campaignId = campaign("seller-limit", productId, 10, 10, 2);
        Instant now = Instant.now();
        int won = 0;
        for (int i = 0; i < 4; i++) {
            try {
                transactions.reserveForSaga(
                        UUID.randomUUID(),
                        "buyer-limit",
                        List.of(new Requested(productId, 1)),
                        now,
                        now.plusSeconds(900),
                        campaignId);
                won++;
            } catch (StockShortException exception) {
                assertThat(exception.getMessage()).contains("limit");
            }
        }
        assertThat(won).isEqualTo(2);
        assertCampaign(campaignId, 10, 8, 2, 0);
    }

    @Test
    void aLargeQuotaStillAdmitsEveryAttemptUnderIt() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID campaignId = campaign("seller-thousand", productId, 1000, 1000, 1000);
        int attempts = 40;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger won = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Instant now = Instant.now();
        for (int i = 0; i < attempts; i++) {
            String customerId = "buyer-thousand-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    transactions.reserveForSaga(
                            UUID.randomUUID(),
                            customerId,
                            List.of(new Requested(productId, 1)),
                            now,
                            now.plusSeconds(900),
                            campaignId);
                    won.incrementAndGet();
                } catch (Throwable failure) {
                    unexpected.set(failure);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(unexpected.get()).isNull();
        assertThat(won.get()).isEqualTo(40);
        assertCampaign(campaignId, 1000, 960, 40, 0);
    }

    @Test
    void expiredCampaignReservationRestoresQuotaAndStock() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID campaignId = campaign("seller-expire", productId, 5, 5, 5);
        Instant now = Instant.now();
        transactions.reserveForSaga(
                UUID.randomUUID(),
                "buyer-expire",
                List.of(new Requested(productId, 1)),
                now,
                now.plusSeconds(900),
                campaignId);
        jdbc.update("UPDATE stock_reservation SET expires_at = ? WHERE campaign_id = ?", Timestamp.from(now.minusSeconds(5)), campaignId);
        assertThat(inventory.expireDue()).isGreaterThanOrEqualTo(1);
        assertCampaign(campaignId, 5, 5, 0, 0);
        var stock = inventory.get(token("buyer-expire", "CUSTOMER"), productId);
        assertThat(stock.available()).isEqualTo(5);
        assertThat(stock.reserved()).isZero();
    }

    @Test
    void commitAndReleaseMoveTheCampaignHold() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID campaignId = campaign("seller-hold", productId, 5, 5, 5);
        Instant now = Instant.now();
        UUID committedOrder = UUID.randomUUID();
        transactions.reserveForSaga(committedOrder, "buyer-hold", List.of(new Requested(productId, 1)), now, now.plusSeconds(900), campaignId);
        UUID releasedOrder = UUID.randomUUID();
        transactions.reserveForSaga(releasedOrder, "buyer-hold-2", List.of(new Requested(productId, 1)), now, now.plusSeconds(900), campaignId);
        UUID committed = jdbc.queryForObject("SELECT id FROM stock_reservation WHERE idempotency_key LIKE ?", UUID.class, "%" + committedOrder.toString().replace("-", "") + "%");
        UUID released = jdbc.queryForObject("SELECT id FROM stock_reservation WHERE idempotency_key LIKE ?", UUID.class, "%" + releasedOrder.toString().replace("-", "") + "%");
        assertThat(transactions.commitForSaga("buyer-hold", committed, Instant.now())).isTrue();
        assertThat(transactions.releaseForSaga(released, Instant.now())).isTrue();
        assertCampaign(campaignId, 5, 4, 0, 1);
        var stock = inventory.get(token("buyer-hold", "CUSTOMER"), productId);
        assertThat(stock.available()).isEqualTo(4);
        assertThat(stock.reserved()).isZero();
    }

    @Test
    void aRepeatedSagaKeyConsumesTheQuotaOnce() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID campaignId = campaign("seller-replay", productId, 5, 5, 5);
        Instant now = Instant.now();
        UUID orderId = UUID.randomUUID();
        transactions.reserveForSaga(orderId, "buyer-replay", List.of(new Requested(productId, 2)), now, now.plusSeconds(900), campaignId);
        transactions.reserveForSaga(orderId, "buyer-replay", List.of(new Requested(productId, 2)), now, now.plusSeconds(900), campaignId);
        assertCampaign(campaignId, 5, 3, 2, 0);
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservation WHERE campaign_id = ?", Integer.class, campaignId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void cancellingACampaignReleasesHeldQuota() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID campaignId = campaign("seller-cancel", productId, 5, 5, 5);
        Instant now = Instant.now();
        transactions.reserveForSaga(UUID.randomUUID(), "buyer-cancel", List.of(new Requested(productId, 2)), now, now.plusSeconds(900), campaignId);
        var cancelled = transactions.cancelCampaign(
                com.bazaar.inventory.stock.InventoryAccess.from(token("seller-cancel", "SELLER")),
                campaignId,
                Instant.now());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertCampaign(campaignId, 5, 5, 0, 0);
        var stock = inventory.get(token("buyer-cancel", "CUSTOMER"), productId);
        assertThat(stock.available()).isEqualTo(5);
        assertThat(stock.reserved()).isZero();
    }

    @Test
    void reconcileStaysBalancedAfterAReservation() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID campaignId = campaign("seller-reconcile", productId, 8, 8, 8);
        Instant now = Instant.now();
        transactions.reserveForSaga(UUID.randomUUID(), "buyer-reconcile", List.of(new Requested(productId, 3)), now, now.plusSeconds(900), campaignId);
        mockMvc.perform(post("/api/v1/inventory/campaigns/" + campaignId + "/reconcile").with(seller("seller-reconcile")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quota").value(8))
                .andExpect(jsonPath("$.remaining").value(5))
                .andExpect(jsonPath("$.held").value(3))
                .andExpect(jsonPath("$.committed").value(0))
                .andExpect(jsonPath("$.balanced").value(true));
    }

    private UUID campaign(String seller, UUID productId, int onHand, int quota, int limit) throws Exception {
        inventory.setOnHand(token(seller, "SELLER"), productId, onHand);
        String windowStart = Instant.now().minusSeconds(60).toString();
        String windowEnd = Instant.now().plusSeconds(3600).toString();
        MvcResult created = mockMvc.perform(post("/api/v1/inventory/campaigns")
                        .with(seller(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"%s","price":9.00,"currency":"USD","quota":%d,"perCustomerLimit":%d,"startsAt":"%s","endsAt":"%s"}
                                """.formatted(productId, quota, limit, windowStart, windowEnd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/api/v1/inventory/campaigns/" + id + "/schedule").with(seller(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        return UUID.fromString(id);
    }

    private void assertCampaign(UUID campaignId, int quota, int remaining, int held, int committed) {
        Integer actualRemaining = jdbc.queryForObject("SELECT remaining FROM flash_campaign WHERE id = ?", Integer.class, campaignId);
        Integer actualHeld = jdbc.queryForObject(
                "SELECT COALESCE(SUM(held), 0) FROM flash_hold WHERE campaign_id = ?",
                Integer.class,
                campaignId);
        Integer actualCommitted = jdbc.queryForObject(
                "SELECT COALESCE(SUM(committed), 0) FROM flash_hold WHERE campaign_id = ?",
                Integer.class,
                campaignId);
        assertThat(actualRemaining).isEqualTo(remaining);
        assertThat(actualHeld).isEqualTo(held);
        assertThat(actualCommitted).isEqualTo(committed);
        assertThat(remaining + held + committed).isEqualTo(quota);
        Integer negative = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flash_campaign WHERE id = ? AND remaining < 0",
                Integer.class,
                campaignId);
        assertThat(negative).isZero();
    }

    private static String command(UUID eventId, UUID orderId, UUID productId, int quantity) {
        return """
                {"eventId":"%s","eventType":"inventory.reservation-requested","eventVersion":1,"aggregateId":"%s","aggregateType":"command","occurredAt":"2026-11-08T00:00:00Z","correlationId":"%s","producer":"order-service","payload":{"orderId":"%s","customerId":"customer-command","items":[{"productId":"%s","quantity":%d}]}}
                """.formatted(eventId, orderId, orderId, orderId, productId, quantity);
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

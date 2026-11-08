package com.bazaar.inventory.stock;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.inventory.api.CampaignResponse;
import com.bazaar.inventory.outbox.OutboxStore;

@Service
public class InventoryTransactions {

    private final JdbcTemplate jdbc;
    private final OutboxStore outbox;
    private final InventoryMetrics metrics;
    private final FlashCampaigns campaigns;

    InventoryTransactions(JdbcTemplate jdbc, OutboxStore outbox, InventoryMetrics metrics, FlashCampaigns campaigns) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.metrics = metrics;
        this.campaigns = campaigns;
    }

    @Transactional
    public StockResponse insertStock(InventoryAccess access, UUID productId, int onHand, Instant now) {
        jdbc.update("""
                INSERT INTO inventory_item (product_id, seller_id, available, reserved, updated_at)
                VALUES (?, ?, ?, 0, ?)
                """, productId, access.subject(), onHand, Timestamp.from(now));
        movement(productId, null, "ADJUST", onHand, now);
        return new StockResponse(productId, onHand, 0, access.subject());
    }

    @Transactional
    public StockResponse updateStock(InventoryAccess access, UUID productId, int onHand, Instant now) {
        List<StockResponse> updated = jdbc.query("""
                UPDATE inventory_item
                SET available = ? - reserved, updated_at = ?
                WHERE product_id = ? AND reserved <= ? AND (seller_id = ? OR ? = TRUE)
                RETURNING product_id, available, reserved, seller_id
                """, (rs, row) -> new StockResponse(
                        rs.getObject("product_id", UUID.class),
                        rs.getInt("available"),
                        rs.getInt("reserved"),
                        rs.getString("seller_id")),
                onHand, Timestamp.from(now), productId, onHand, access.subject(), access.admin());
        if (!updated.isEmpty()) {
            movement(productId, null, "ADJUST", onHand, now);
            return updated.getFirst();
        }
        StockResponse current = findStock(productId);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock was not found.");
        }
        if (!access.admin() && !current.sellerId().equals(access.subject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change this stock.");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "onHand is below the reserved quantity.");
    }

    @Transactional(readOnly = true)
    public StockResponse get(UUID productId) {
        StockResponse stock = findStock(productId);
        if (stock == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock was not found.");
        }
        return stock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireDue(Instant now) {
        List<Held> due = jdbc.query("""
                SELECT id, product_id, quantity, requester_id, campaign_id
                FROM stock_reservation
                WHERE status = 'HELD' AND expires_at <= ?
                FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Held(
                        rs.getObject("id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getInt("quantity"),
                        rs.getString("requester_id"),
                        rs.getObject("campaign_id", UUID.class)),
                Timestamp.from(now));
        int expired = 0;
        for (Held held : due) {
            int changed = jdbc.update("""
                    UPDATE stock_reservation
                    SET status = 'EXPIRED', updated_at = ?
                    WHERE id = ? AND status = 'HELD'
                    """, Timestamp.from(now), held.id());
            if (changed == 1) {
                returnStock(held.productId(), held.quantity(), now);
                if (held.campaignId() != null) {
                    campaigns.restore(held.campaignId(), held.customerId(), held.quantity(), now);
                }
                movement(held.productId(), held.id(), "EXPIRE", held.quantity(), now);
                reservationEvent("inventory.expired", held.id(), held.productId(), held.quantity(), "EXPIRED", now, null);
                expired++;
            }
        }
        metrics.expired(expired);
        return expired;
    }

    @Transactional
    public ReserveOutcome reserve(
            InventoryAccess access,
            String key,
            String hash,
            UUID productId,
            int quantity,
            Instant now,
            Instant expiresAt) {
        ReservationResponse existing = findByKey(key);
        if (existing != null) {
            return new ReserveOutcome(replay(access, key, hash), false);
        }
        int taken = jdbc.update("""
                UPDATE inventory_item
                SET available = available - ?, reserved = reserved + ?, updated_at = ?
                WHERE product_id = ? AND available >= ?
                """, quantity, quantity, Timestamp.from(now), productId, quantity);
        if (taken != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough stock is available.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO stock_reservation (
                    id, product_id, quantity, status, idempotency_key, request_hash,
                    requester_id, expires_at, created_at, updated_at, campaign_id)
                VALUES (?, ?, ?, 'HELD', ?, ?, ?, ?, ?, ?, NULL)
                """, id, productId, quantity, key, hash, access.subject(),
                Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now));
        movement(productId, id, "RESERVE", quantity, now);
        reservationEvent("inventory.reserved", id, productId, quantity, "HELD", now, null);
        return new ReserveOutcome(findReservation(id), true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserveForSaga(UUID orderId, String customerId, java.util.List<Requested> items, Instant now, Instant expiresAt) {
        reserveForSaga(orderId, customerId, items, now, expiresAt, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserveForSaga(
            UUID orderId,
            String customerId,
            java.util.List<Requested> items,
            Instant now,
            Instant expiresAt,
            UUID campaignId) {
        if (campaignId != null && items.size() != 1) {
            throw new StockShortException("A flash sale checkout has one product.");
        }
        java.util.List<Requested> ordered = new java.util.ArrayList<>(items);
        ordered.sort(java.util.Comparator.comparing(Requested::productId));
        for (Requested item : ordered) {
            String key = sagaKey(orderId, item.productId());
            ReservationResponse existing = findByKey(key);
            if (existing != null) {
                reservationEvent("inventory.reserved", existing.id(), item.productId(), item.quantity(), "HELD", now, orderId);
                continue;
            }
            int taken = jdbc.update("""
                    UPDATE inventory_item
                    SET available = available - ?, reserved = reserved + ?, updated_at = ?
                    WHERE product_id = ? AND available >= ?
                    """, item.quantity(), item.quantity(), Timestamp.from(now), item.productId(), item.quantity());
            if (taken != 1) {
                throw new StockShortException("Not enough stock is available.");
            }
            if (campaignId != null) {
                campaigns.consume(campaignId, item.productId(), customerId, item.quantity(), now);
            }
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO stock_reservation (
                        id, product_id, quantity, status, idempotency_key, request_hash,
                        requester_id, expires_at, created_at, updated_at, campaign_id)
                    VALUES (?, ?, ?, 'HELD', ?, ?, ?, ?, ?, ?, ?)
                    """, id, item.productId(), item.quantity(), key, sagaHash(item.productId(), item.quantity(), customerId),
                    customerId, Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now), campaignId);
            movement(item.productId(), id, "RESERVE", item.quantity(), now);
            reservationEvent("inventory.reserved", id, item.productId(), item.quantity(), "HELD", now, orderId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean commitForSaga(String customerId, UUID reservationId, Instant now) {
        ReservationResponse current = findReservation(reservationId);
        if (current == null) {
            throw new StockShortException("Reservation was not found.");
        }
        if ("COMMITTED".equals(current.status())) {
            return false;
        }
        lockProduct(current.productId());
        int changed = jdbc.update("""
                UPDATE stock_reservation
                SET status = 'COMMITTED', updated_at = ?
                WHERE id = ? AND status = 'HELD' AND expires_at > ?
                """, Timestamp.from(now), reservationId, Timestamp.from(now));
        if (changed != 1) {
            throw new StockShortException("This reservation is no longer held.");
        }
        int reduced = jdbc.update("""
                UPDATE inventory_item
                SET reserved = reserved - ?, updated_at = ?
                WHERE product_id = ? AND reserved >= ?
                """, current.quantity(), Timestamp.from(now), current.productId(), current.quantity());
        if (reduced != 1) {
            throw new StockShortException("Reserved stock could not be committed.");
        }
        movement(current.productId(), reservationId, "COMMIT", current.quantity(), now);
        UUID campaignId = campaignOf(reservationId);
        if (campaignId != null) {
            campaigns.commitHold(campaignId, current.requesterId(), current.quantity());
        }
        reservationEvent("inventory.committed", reservationId, current.productId(), current.quantity(), "COMMITTED", now, null);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean releaseForSaga(UUID reservationId, Instant now) {
        ReservationResponse current = findReservation(reservationId);
        if (current == null || "RELEASED".equals(current.status()) || "EXPIRED".equals(current.status())) {
            return false;
        }
        if ("COMMITTED".equals(current.status())) {
            throw new StockShortException("A committed reservation cannot be released.");
        }
        lockProduct(current.productId());
        int changed = jdbc.update("""
                UPDATE stock_reservation
                SET status = 'RELEASED', updated_at = ?
                WHERE id = ? AND status = 'HELD'
                """, Timestamp.from(now), reservationId);
        if (changed != 1) {
            return false;
        }
        returnStock(current.productId(), current.quantity(), now);
        restoreCampaign(reservationId, current.requesterId(), current.quantity(), now);
        movement(current.productId(), reservationId, "RELEASE", current.quantity(), now);
        reservationEvent("inventory.released", reservationId, current.productId(), current.quantity(), "RELEASED", now, null);
        return true;
    }

    @Transactional(readOnly = true)
    public ReservationResponse replay(InventoryAccess access, String key, String hash) {
        ReservationResponse existing = findByKey(key);
        if (existing == null || !existing.requesterId().equals(access.subject()) || !existing.requestHash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key is already in use.");
        }
        return existing.withoutSecrets();
    }

    @Transactional
    public ReservationResponse release(InventoryAccess access, UUID id, Instant now) {
        ReservationResponse current = required(id);
        if (!current.requesterId().equals(access.subject()) && !access.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change this reservation.");
        }
        if ("COMMITTED".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A committed reservation cannot be released.");
        }
        lockProduct(current.productId());
        int changed = jdbc.update("""
                UPDATE stock_reservation
                SET status = 'RELEASED', updated_at = ?
                WHERE id = ? AND status = 'HELD' AND (requester_id = ? OR ? = TRUE)
                """, Timestamp.from(now), id, access.subject(), access.admin());
        if (changed == 1) {
            returnStock(current.productId(), current.quantity(), now);
            restoreCampaign(id, current.requesterId(), current.quantity(), now);
            movement(current.productId(), id, "RELEASE", current.quantity(), now);
            reservationEvent("inventory.released", id, current.productId(), current.quantity(), "RELEASED", now, null);
            return findReservation(id).withoutSecrets();
        }
        ReservationResponse latest = findReservation(id);
        if ("COMMITTED".equals(latest.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A committed reservation cannot be released.");
        }
        return latest.withoutSecrets();
    }

    @Transactional
    public ReservationResponse commit(InventoryAccess access, UUID id, Instant now) {
        ReservationResponse current = required(id);
        if (!current.requesterId().equals(access.subject()) && !access.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change this reservation.");
        }
        if ("RELEASED".equals(current.status()) || "EXPIRED".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This reservation is no longer held.");
        }
        int changed = jdbc.update("""
                UPDATE stock_reservation
                SET status = 'COMMITTED', updated_at = ?
                WHERE id = ? AND status = 'HELD' AND expires_at > ? AND (requester_id = ? OR ? = TRUE)
                """, Timestamp.from(now), id, Timestamp.from(now), access.subject(), access.admin());
        if (changed == 1) {
            int reduced = jdbc.update("""
                    UPDATE inventory_item
                    SET reserved = reserved - ?, updated_at = ?
                    WHERE product_id = ? AND reserved >= ?
                    """, current.quantity(), Timestamp.from(now), current.productId(), current.quantity());
            if (reduced != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Reserved stock could not be committed.");
            }
            movement(current.productId(), id, "COMMIT", current.quantity(), now);
            reservationEvent("inventory.committed", id, current.productId(), current.quantity(), "COMMITTED", now, null);
        }
        ReservationResponse latest = findReservation(id);
        if (!"COMMITTED".equals(latest.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This reservation is no longer held.");
        }
        return latest.withoutSecrets();
    }

    private void lockProduct(UUID productId) {
        jdbc.query(
                "SELECT product_id FROM inventory_item WHERE product_id = ? FOR UPDATE",
                (rs, row) -> rs.getObject("product_id", UUID.class),
                productId);
    }

    private void returnStock(UUID productId, int quantity, Instant now) {
        int returned = jdbc.update("""
                UPDATE inventory_item
                SET available = available + ?, reserved = reserved - ?, updated_at = ?
                WHERE product_id = ? AND reserved >= ?
                """, quantity, quantity, Timestamp.from(now), productId, quantity);
        if (returned != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reserved stock could not be returned.");
        }
    }

    private ReservationResponse required(UUID id) {
        ReservationResponse current = findReservation(id);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation was not found.");
        }
        return current;
    }

    private StockResponse findStock(UUID productId) {
        List<StockResponse> rows = jdbc.query("""
                SELECT product_id, available, reserved, seller_id
                FROM inventory_item
                WHERE product_id = ?
                """, (rs, row) -> new StockResponse(
                        rs.getObject("product_id", UUID.class),
                        rs.getInt("available"),
                        rs.getInt("reserved"),
                        rs.getString("seller_id")),
                productId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ReservationResponse findByKey(String key) {
        List<ReservationResponse> rows = jdbc.query("""
                SELECT id, product_id, quantity, status, requester_id, request_hash, expires_at, created_at
                FROM stock_reservation
                WHERE idempotency_key = ?
                """, this::reservation, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ReservationResponse findReservation(UUID id) {
        List<ReservationResponse> rows = jdbc.query("""
                SELECT id, product_id, quantity, status, requester_id, request_hash, expires_at, created_at
                FROM stock_reservation
                WHERE id = ?
                """, this::reservation, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ReservationResponse reservation(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ReservationResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getInt("quantity"),
                rs.getString("status"),
                rs.getString("requester_id"),
                rs.getString("request_hash"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private void reservationEvent(String eventType, UUID reservationId, UUID productId, int quantity, String status, Instant now, UUID orderId) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("productId", productId.toString());
        payload.put("quantity", quantity);
        payload.put("status", status);
        UUID correlation = reservationId;
        if (orderId != null) {
            payload.put("orderId", orderId.toString());
            correlation = orderId;
        }
        outbox.add(eventType, "inventory", reservationId.toString(), correlation, payload, now);
    }

    private static String sagaKey(UUID orderId, UUID productId) {
        return "s" + orderId.toString().replace("-", "") + productId.toString().replace("-", "");
    }

    private static String sagaHash(UUID productId, int quantity, String customerId) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((productId + "|" + quantity + "|" + customerId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public record Requested(UUID productId, int quantity) {
    }

    @Transactional
    public CampaignResponse cancelCampaign(InventoryAccess access, UUID id, Instant now) {
        List<UUID> products = jdbc.query(
                "SELECT product_id FROM flash_campaign WHERE id = ?",
                (rs, row) -> rs.getObject("product_id", UUID.class),
                id);
        if (products.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign was not found.");
        }
        jdbc.query("SELECT product_id FROM inventory_item WHERE product_id = ? FOR UPDATE",
                (rs, row) -> rs.getObject("product_id", UUID.class),
                products.getFirst());
        campaigns.markCancelled(access, id, now);
        List<UUID> held = jdbc.query("""
                SELECT id FROM stock_reservation
                WHERE campaign_id = ? AND status = 'HELD'
                FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), id);
        for (UUID reservationId : held) {
            releaseHeld(reservationId, now);
        }
        return campaigns.visible(access, id, now);
    }

    private void releaseHeld(UUID reservationId, Instant now) {
        ReservationResponse current = findReservation(reservationId);
        if (current == null || !"HELD".equals(current.status())) {
            return;
        }
        int changed = jdbc.update("""
                UPDATE stock_reservation
                SET status = 'RELEASED', updated_at = ?
                WHERE id = ? AND status = 'HELD'
                """, Timestamp.from(now), reservationId);
        if (changed != 1) {
            return;
        }
        returnStock(current.productId(), current.quantity(), now);
        restoreCampaign(reservationId, current.requesterId(), current.quantity(), now);
        movement(current.productId(), reservationId, "RELEASE", current.quantity(), now);
        reservationEvent("inventory.released", reservationId, current.productId(), current.quantity(), "RELEASED", now, null);
    }

    private void restoreCampaign(UUID reservationId, String customerId, int quantity, Instant now) {
        UUID campaignId = campaignOf(reservationId);
        if (campaignId != null) {
            campaigns.restore(campaignId, customerId, quantity, now);
        }
    }

    private UUID campaignOf(UUID reservationId) {
        List<UUID> ids = jdbc.query(
                "SELECT campaign_id FROM stock_reservation WHERE id = ?",
                (rs, row) -> rs.getObject("campaign_id", UUID.class),
                reservationId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private void movement(UUID productId, UUID reservationId, String type, int quantity, Instant now) {
        jdbc.update("""
                INSERT INTO inventory_movement (id, product_id, reservation_id, movement_type, quantity, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), productId, reservationId, type, quantity, Timestamp.from(now));
    }

    private record Held(UUID id, UUID productId, int quantity, String customerId, UUID campaignId) {
    }
}

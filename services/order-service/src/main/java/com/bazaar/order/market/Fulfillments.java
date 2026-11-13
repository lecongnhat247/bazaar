package com.bazaar.order.market;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manual/mock fulfillment, one group per (order, seller). A seller can only act on its own
 * group. Groups move independently, which gives partial fulfillment of a multi-seller order.
 */
@Service
public class Fulfillments {

    static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9-]{4,40}");

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "PENDING", Set.of("PACKED", "CANCELLED"),
            "PACKED", Set.of("SHIPPED", "CANCELLED"),
            "SHIPPED", Set.of("DELIVERED"));

    private final JdbcTemplate jdbc;
    private final Ledger ledger;

    public Fulfillments(JdbcTemplate jdbc, Ledger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    /** Creates a PENDING group per seller when an order confirms. Idempotent. */
    @Transactional
    public void open(UUID orderId, Instant now) {
        List<String> sellers = jdbc.queryForList(
                "SELECT DISTINCT seller_id FROM order_item WHERE order_id = ? ORDER BY seller_id",
                String.class, orderId);
        for (String sellerId : sellers) {
            UUID id = UUID.randomUUID();
            int created = jdbc.update("""
                    INSERT INTO fulfillment (id, order_id, seller_id, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'PENDING', ?, ?)
                    ON CONFLICT (order_id, seller_id) DO NOTHING
                    """, id, orderId, sellerId, Timestamp.from(now), Timestamp.from(now));
            if (created == 1) {
                event(id, null, "PENDING", "system", "Order confirmed", now);
            }
        }
    }

    @Transactional
    public FulfillmentView advance(
            UUID orderId, String sellerId, String actor, String target, String reference, String note, Instant now) {
        Row row = lock(orderId, sellerId);
        if (row == null) {
            backfill(orderId, sellerId, now);
            row = lock(orderId, sellerId);
            if (row == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fulfillment group not found.");
            }
        }
        if (row.status().equals(target)) {
            return view(row.id());
        }
        if (!ALLOWED.getOrDefault(row.status(), Set.of()).contains(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot move fulfillment from " + row.status() + " to " + target + ".");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if ("SHIPPED".equals(target) && (reference == null || !REFERENCE.matcher(reference).matches())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "shipmentReference is required (4-40 characters: letters, digits or -).");
        }
        if ("CANCELLED".equals(target) && cleanNote == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A cancellation needs a reason in note.");
        }
        Timestamp stamp = Timestamp.from(now);
        switch (target) {
            case "PACKED" -> jdbc.update(
                    "UPDATE fulfillment SET status = 'PACKED', packed_at = ?, updated_at = ? WHERE id = ?",
                    stamp, stamp, row.id());
            case "SHIPPED" -> jdbc.update("""
                    UPDATE fulfillment SET status = 'SHIPPED', shipment_reference = ?, shipped_at = ?, updated_at = ?
                    WHERE id = ?
                    """, reference, stamp, stamp, row.id());
            case "DELIVERED" -> jdbc.update(
                    "UPDATE fulfillment SET status = 'DELIVERED', delivered_at = ?, updated_at = ? WHERE id = ?",
                    stamp, stamp, row.id());
            case "CANCELLED" -> jdbc.update(
                    "UPDATE fulfillment SET status = 'CANCELLED', cancelled_at = ?, updated_at = ? WHERE id = ?",
                    stamp, stamp, row.id());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported status.");
        }
        event(row.id(), row.status(), target, actor, "SHIPPED".equals(target) ? "Shipment " + reference : cleanNote,
                now);
        if ("CANCELLED".equals(target)) {
            ledger.reverseGroup(orderId, sellerId, "Seller cancelled fulfillment: " + cleanNote, actor, now);
        }
        return view(row.id());
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<FulfillmentView>> forOrders(Collection<UUID> orderIds, String sellerFilter) {
        Map<UUID, List<FulfillmentView>> result = new HashMap<>();
        if (orderIds.isEmpty()) {
            return result;
        }
        String marks = String.join(",", java.util.Collections.nCopies(orderIds.size(), "?"));
        List<Object> args = new ArrayList<>(orderIds);
        String filter = "";
        if (sellerFilter != null) {
            filter = " AND seller_id = ?";
            args.add(sellerFilter);
        }
        List<UUID> ids = new ArrayList<>();
        Map<UUID, UUID> orderOf = new HashMap<>();
        List<FulfillmentView> shells = new ArrayList<>();
        jdbc.query("SELECT * FROM fulfillment WHERE order_id IN (" + marks + ")" + filter
                + " ORDER BY order_id, seller_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    UUID orderId = rs.getObject("order_id", UUID.class);
                    ids.add(id);
                    orderOf.put(id, orderId);
                    shells.add(shell(rs));
                }, args.toArray());
        Map<UUID, List<FulfillmentView.Event>> events = events(ids);
        for (FulfillmentView shell : shells) {
            FulfillmentView full = new FulfillmentView(shell.id(), shell.sellerId(), shell.status(),
                    shell.shipmentReference(), shell.packedAt(), shell.shippedAt(), shell.deliveredAt(),
                    shell.cancelledAt(), shell.updatedAt(), events.getOrDefault(shell.id(), List.of()));
            result.computeIfAbsent(orderOf.get(shell.id()), k -> new ArrayList<>()).add(full);
        }
        return result;
    }

    /** Order-level status derived from its seller groups. */
    public static String summary(List<FulfillmentView> groups) {
        if (groups == null || groups.isEmpty()) {
            return "NONE";
        }
        long delivered = groups.stream().filter(g -> g.status().equals("DELIVERED")).count();
        long cancelled = groups.stream().filter(g -> g.status().equals("CANCELLED")).count();
        long shipped = groups.stream().filter(g -> g.status().equals("SHIPPED")).count();
        long open = groups.size() - delivered - cancelled - shipped;
        if (cancelled == groups.size()) {
            return "CANCELLED";
        }
        if (delivered + cancelled == groups.size()) {
            return "DELIVERED";
        }
        if (open == 0) {
            return "SHIPPED";
        }
        return delivered + shipped > 0 ? "PARTIALLY_SHIPPED" : "PENDING";
    }

    /** Groups for orders confirmed before M4 are created lazily on first seller action. */
    private void backfill(UUID orderId, String sellerId, Instant now) {
        boolean confirmed = !jdbc.queryForList(
                "SELECT 1 FROM customer_order WHERE id = ? AND status = 'CONFIRMED'", orderId).isEmpty();
        boolean sellerLines = !jdbc.queryForList(
                "SELECT 1 FROM order_item WHERE order_id = ? AND seller_id = ?", orderId, sellerId).isEmpty();
        if (confirmed && sellerLines) {
            open(orderId, now);
            ledger.recordSale(orderId, now);
        }
    }

    private Row lock(UUID orderId, String sellerId) {
        return jdbc.query("""
                SELECT id, status FROM fulfillment WHERE order_id = ? AND seller_id = ? FOR UPDATE
                """, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("status")), orderId, sellerId)
                .stream().findFirst().orElse(null);
    }

    private FulfillmentView view(UUID id) {
        FulfillmentView shell = jdbc.query("SELECT * FROM fulfillment WHERE id = ?", (rs, n) -> shell(rs), id).get(0);
        return new FulfillmentView(shell.id(), shell.sellerId(), shell.status(), shell.shipmentReference(),
                shell.packedAt(), shell.shippedAt(), shell.deliveredAt(), shell.cancelledAt(), shell.updatedAt(),
                events(List.of(id)).getOrDefault(id, List.of()));
    }

    private Map<UUID, List<FulfillmentView.Event>> events(List<UUID> ids) {
        Map<UUID, List<FulfillmentView.Event>> events = new HashMap<>();
        if (ids.isEmpty()) {
            return events;
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.query("SELECT * FROM fulfillment_event WHERE fulfillment_id IN (" + marks
                + ") ORDER BY fulfillment_id, created_at, id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> events
                        .computeIfAbsent(rs.getObject("fulfillment_id", UUID.class), k -> new ArrayList<>())
                        .add(new FulfillmentView.Event(rs.getString("from_status"), rs.getString("to_status"),
                                rs.getString("actor"), rs.getString("note"),
                                rs.getTimestamp("created_at").toInstant())),
                ids.toArray());
        return events;
    }

    private void event(UUID fulfillmentId, String from, String to, String actor, String note, Instant now) {
        jdbc.update("""
                INSERT INTO fulfillment_event (id, fulfillment_id, from_status, to_status, actor, note, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), fulfillmentId, from, to, actor, note, Timestamp.from(now));
    }

    private static FulfillmentView shell(ResultSet rs) throws SQLException {
        return new FulfillmentView(
                rs.getObject("id", UUID.class),
                rs.getString("seller_id"),
                rs.getString("status"),
                rs.getString("shipment_reference"),
                instant(rs, "packed_at"),
                instant(rs, "shipped_at"),
                instant(rs, "delivered_at"),
                instant(rs, "cancelled_at"),
                rs.getTimestamp("updated_at").toInstant(),
                List.of());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record Row(UUID id, String status) {
    }
}

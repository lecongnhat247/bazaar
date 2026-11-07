package com.bazaar.order.checkout;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class OrderTransactions {

    private final JdbcTemplate jdbc;

    OrderTransactions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public OrderRow findByKey(String key) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM customer_order WHERE idempotency_key = ?",
                (rs, row) -> rs.getObject("id", UUID.class),
                key);
        return ids.isEmpty() ? null : required(ids.getFirst());
    }

    @Transactional
    public OrderRow insert(
            OrderAccess access,
            String key,
            String hash,
            String outcome,
            String currency,
            BigDecimal total,
            List<Line> lines,
            Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer_order (
                    id, customer_id, status, currency, total_amount, outcome,
                    idempotency_key, request_hash, created_at, updated_at)
                VALUES (?, ?, 'PENDING_PAYMENT', ?, ?, ?, ?, ?, ?, ?)
                """, id, access.subject(), currency, total, outcome, key, hash,
                Timestamp.from(now), Timestamp.from(now));
        for (Line line : lines) {
            jdbc.update("""
                    INSERT INTO order_item (
                        id, order_id, product_id, seller_id, sku, product_name,
                        unit_price, quantity, line_total, reservation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), id, line.productId(), line.sellerId(), line.sku(), line.name(),
                    line.unitPrice(), line.quantity(), line.lineTotal(), line.reservationId());
        }
        history(id, "PENDING_PAYMENT", 1, now);
        return required(id);
    }

    @Transactional
    public OrderRow savePayment(UUID orderId, UUID paymentId, Instant now) {
        jdbc.update("""
                UPDATE customer_order
                SET payment_id = ?, updated_at = ?
                WHERE id = ? AND payment_id IS NULL
                """, paymentId, Timestamp.from(now), orderId);
        return required(orderId);
    }

    @Transactional
    public OrderRow mark(UUID orderId, String status, Instant now) {
        int changed = jdbc.update("""
                UPDATE customer_order
                SET status = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING_PAYMENT'
                """, status, Timestamp.from(now), orderId);
        if (changed == 1) {
            Integer seq = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(seq), 0) + 1 FROM order_status_history WHERE order_id = ?",
                    Integer.class,
                    orderId);
            history(orderId, status, seq == null ? 1 : seq, now);
        }
        OrderRow latest = required(orderId);
        if (!status.equals(latest.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order can no longer change.");
        }
        return latest;
    }

    @Transactional(readOnly = true)
    public OrderRow get(OrderAccess access, UUID id) {
        OrderRow row = required(id);
        if (!row.customerId().equals(access.subject()) && !access.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot read this order.");
        }
        return row;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> list(OrderAccess access) {
        String sql = access.admin()
                ? "SELECT id FROM customer_order ORDER BY created_at DESC LIMIT 20"
                : "SELECT id FROM customer_order WHERE customer_id = ? ORDER BY created_at DESC LIMIT 20";
        List<UUID> ids = access.admin()
                ? jdbc.query(sql, (rs, row) -> rs.getObject("id", UUID.class))
                : jdbc.query(sql, (rs, row) -> rs.getObject("id", UUID.class), access.subject());
        List<OrderResponse> orders = new ArrayList<>();
        for (UUID id : ids) {
            orders.add(response(required(id)));
        }
        return orders;
    }

    public OrderResponse response(OrderRow row) {
        List<ItemResponse> items = jdbc.query("""
                SELECT product_id, seller_id, sku, product_name, unit_price, quantity, line_total, reservation_id
                FROM order_item
                WHERE order_id = ?
                ORDER BY product_id
                """, (rs, n) -> new ItemResponse(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("seller_id"),
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("unit_price"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("line_total"),
                        rs.getObject("reservation_id", UUID.class)),
                row.id());
        List<HistoryResponse> history = jdbc.query("""
                SELECT status, created_at
                FROM order_status_history
                WHERE order_id = ?
                ORDER BY seq
                """, (rs, n) -> new HistoryResponse(rs.getString("status"), rs.getTimestamp("created_at").toInstant()),
                row.id());
        return new OrderResponse(
                row.id(),
                row.customerId(),
                row.status(),
                row.currency(),
                row.total(),
                row.paymentId(),
                items,
                history,
                row.createdAt(),
                row.updatedAt());
    }

    private OrderRow required(UUID id) {
        List<OrderRow> rows = jdbc.query("""
                SELECT id, customer_id, status, currency, total_amount, outcome, request_hash, payment_id,
                       created_at, updated_at
                FROM customer_order
                WHERE id = ?
                """, (rs, n) -> new OrderRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("customer_id"),
                        rs.getString("status"),
                        rs.getString("currency").trim(),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("outcome"),
                        rs.getString("request_hash").trim(),
                        rs.getObject("payment_id", UUID.class),
                        List.of(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order was not found.");
        }
        OrderRow row = rows.getFirst();
        return new OrderRow(
                row.id(),
                row.customerId(),
                row.status(),
                row.currency(),
                row.total(),
                row.outcome(),
                row.requestHash(),
                row.paymentId(),
                itemsOf(row.id()),
                row.createdAt(),
                row.updatedAt());
    }

    private List<Line> itemsOf(UUID orderId) {
        return jdbc.query("""
                SELECT product_id, seller_id, sku, product_name, unit_price, quantity, line_total, reservation_id
                FROM order_item
                WHERE order_id = ?
                ORDER BY product_id
                """, (rs, n) -> new Line(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("seller_id"),
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("unit_price"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("line_total"),
                        rs.getObject("reservation_id", UUID.class)),
                orderId);
    }

    private void history(UUID orderId, String status, int seq, Instant now) {
        jdbc.update("""
                INSERT INTO order_status_history (id, order_id, status, seq, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), orderId, status, seq, Timestamp.from(now));
    }

    record Line(
            UUID productId,
            String sellerId,
            String sku,
            String name,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal,
            UUID reservationId) {
    }

    record OrderRow(
            UUID id,
            String customerId,
            String status,
            String currency,
            BigDecimal total,
            String outcome,
            String requestHash,
            UUID paymentId,
            List<Line> lines,
            Instant createdAt,
            Instant updatedAt) {
    }
}

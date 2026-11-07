package com.bazaar.payment.pay;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.payment.outbox.OutboxStore;

@Service
class PaymentTransactions {

    private final JdbcTemplate jdbc;
    private final OutboxStore outbox;

    PaymentTransactions(JdbcTemplate jdbc, OutboxStore outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Transactional
    public CreateOutcome create(
            PaymentAccess access,
            String key,
            String hash,
            UUID orderId,
            BigDecimal amount,
            String currency,
            Instant now) {
        PaymentRow existing = findByKey(key);
        if (existing != null) {
            return new CreateOutcome(replay(access, key, hash), false);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment (
                    id, order_id, payer_id, amount, currency, status,
                    idempotency_key, request_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                """, id, orderId, access.subject(), amount, currency, key, hash,
                Timestamp.from(now), Timestamp.from(now));
        attempt(id, "CREATE", "PENDING", now);
        return new CreateOutcome(load(id), true);
    }

    @Transactional(readOnly = true)
    public PaymentResponse replay(PaymentAccess access, String key, String hash) {
        PaymentRow existing = findByKey(key);
        if (existing == null || !existing.payerId().equals(access.subject()) || !existing.requestHash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key is already in use.");
        }
        return load(existing.id());
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(PaymentAccess access, UUID id) {
        PaymentRow row = required(id);
        if (!row.payerId().equals(access.subject()) && !access.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot read this payment.");
        }
        return load(id);
    }

    @Transactional
    public PaymentResponse change(PaymentAccess access, UUID id, String status, String attemptType, Instant now) {
        PaymentRow current = required(id);
        if (!current.payerId().equals(access.subject()) && !access.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change this payment.");
        }
        if (status.equals(current.status())) {
            return load(id);
        }
        if (!"PENDING".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This payment can no longer change.");
        }
        int changed = jdbc.update("""
                UPDATE payment
                SET status = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, status, Timestamp.from(now), id);
        if (changed == 1) {
            attempt(id, attemptType, status, now);
            outbox.add(eventType(status), "payment", id.toString(), id, java.util.Map.of(
                    "orderId", current.orderId().toString(),
                    "amount", current.amount().toPlainString(),
                    "currency", current.currency(),
                    "status", status), now);
        }
        PaymentRow latest = required(id);
        if (!status.equals(latest.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This payment can no longer change.");
        }
        return load(id);
    }

    private static String eventType(String status) {
        return switch (status) {
            case "SUCCEEDED" -> "payment.succeeded";
            case "FAILED" -> "payment.failed";
            case "CANCELLED" -> "payment.cancelled";
            default -> "payment." + status.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private PaymentRow required(UUID id) {
        PaymentRow row = findById(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment was not found.");
        }
        return row;
    }

    private PaymentResponse load(UUID id) {
        PaymentRow row = required(id);
        List<AttemptResponse> attempts = jdbc.query("""
                SELECT id, attempt_type, result_status, created_at
                FROM payment_attempt
                WHERE payment_id = ?
                ORDER BY created_at, id
                """, (rs, n) -> new AttemptResponse(
                        rs.getObject("id", UUID.class),
                        rs.getString("attempt_type"),
                        rs.getString("result_status"),
                        rs.getTimestamp("created_at").toInstant()),
                id);
        return new PaymentResponse(
                row.id(),
                row.orderId(),
                row.payerId(),
                row.amount(),
                row.currency(),
                row.status(),
                attempts,
                row.createdAt(),
                row.updatedAt());
    }

    private PaymentRow findByKey(String key) {
        List<PaymentRow> rows = jdbc.query("""
                SELECT id, order_id, payer_id, amount, currency, status, request_hash, created_at, updated_at
                FROM payment
                WHERE idempotency_key = ?
                """, this::row, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private PaymentRow findById(UUID id) {
        List<PaymentRow> rows = jdbc.query("""
                SELECT id, order_id, payer_id, amount, currency, status, request_hash, created_at, updated_at
                FROM payment
                WHERE id = ?
                """, this::row, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private PaymentRow row(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return new PaymentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("payer_id"),
                rs.getBigDecimal("amount"),
                rs.getString("currency").trim(),
                rs.getString("status"),
                rs.getString("request_hash"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private void attempt(UUID paymentId, String type, String result, Instant now) {
        jdbc.update("""
                INSERT INTO payment_attempt (id, payment_id, attempt_type, result_status, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), paymentId, type, result, Timestamp.from(now));
    }

    private record PaymentRow(
            UUID id,
            UUID orderId,
            String payerId,
            BigDecimal amount,
            String currency,
            String status,
            String requestHash,
            Instant createdAt,
            Instant updatedAt) {
    }
}

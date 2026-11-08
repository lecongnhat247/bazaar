package com.bazaar.payment.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void add(
            String eventType,
            String aggregateType,
            String aggregateId,
            UUID correlationId,
            Object payload,
            Instant occurredAt) {
        jdbc.update("""
                INSERT INTO outbox (
                    id, event_id, aggregate_type, aggregate_id, event_type, event_version,
                    correlation_id, payload, occurred_at, status, retry_count)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?::jsonb, ?, 'PENDING', 0)
                """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                correlationId,
                objectMapper.writeValueAsString(payload),
                Timestamp.from(occurredAt));
    }

    @Transactional
    public int recoverStale(Instant claimedBefore, int maxAttempts, Instant retryAt) {
        return jdbc.update("""
                UPDATE outbox
                SET retry_count = retry_count + 1,
                    last_error = 'publication was interrupted',
                    claimed_at = NULL,
                    status = CASE WHEN retry_count + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                    next_attempt_at = CASE WHEN retry_count + 1 >= ? THEN NULL ELSE ?::timestamptz END
                WHERE status = 'SENDING' AND claimed_at < ?
                """, maxAttempts, maxAttempts, Timestamp.from(retryAt), Timestamp.from(claimedBefore));
    }

    @Transactional
    public List<OutboxMessage> claim(int batchSize, Instant now) {
        return jdbc.query("""
                WITH claimed AS (
                    SELECT id
                    FROM outbox
                    WHERE status = 'PENDING'
                      AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                    ORDER BY occurred_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE outbox AS message
                SET status = 'SENDING', claimed_at = ?
                FROM claimed
                WHERE message.id = claimed.id
                RETURNING message.id, message.event_id, message.aggregate_type, message.aggregate_id,
                          message.event_type, message.event_version, message.correlation_id, message.causation_id,
                          message.payload::text, message.occurred_at, message.retry_count
                """, this::message, Timestamp.from(now), batchSize, Timestamp.from(now));
    }

    public void markPublished(UUID id, Instant now) {
        jdbc.update("""
                UPDATE outbox
                SET status = 'PUBLISHED', published_at = ?, last_error = NULL
                WHERE id = ?
                """, Timestamp.from(now), id);
    }

    public String markFailed(UUID id, String error, int maxAttempts, Instant retryAt) {
        return jdbc.queryForObject("""
                UPDATE outbox
                SET retry_count = retry_count + 1,
                    last_error = ?,
                    claimed_at = NULL,
                    status = CASE WHEN retry_count + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                    next_attempt_at = CASE WHEN retry_count + 1 >= ? THEN NULL ELSE ?::timestamptz END
                WHERE id = ?
                RETURNING status
                """, String.class, trim(error), maxAttempts, maxAttempts, Timestamp.from(retryAt), id);
    }

    public int pending() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status IN ('PENDING', 'SENDING')",
                Integer.class);
        return count == null ? 0 : count;
    }

    public int dead() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status = 'DEAD'",
                Integer.class);
        return count == null ? 0 : count;
    }

    private OutboxMessage message(ResultSet rs, int ignored) throws SQLException {
        return new OutboxMessage(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getString("payload"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getInt("retry_count"));
    }

    private static String trim(String error) {
        if (error == null) {
            return "publication failed";
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}

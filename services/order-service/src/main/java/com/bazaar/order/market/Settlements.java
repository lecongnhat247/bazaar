package com.bazaar.order.market;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mock settlement. A run bundles the unsettled SELLER_PAYABLE entries of DELIVERED groups per
 * (seller, currency) into a settlement row. No money moves and no payout provider is called.
 *
 * <p>Idempotency: a run key is recorded once (replays return the original result), runs are
 * serialised by an advisory lock, and each ledger entry can be linked to at most one settlement
 * (UNIQUE ledger_entry_id), so a double settlement is impossible even with different keys.
 */
@Service
public class Settlements {

    static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final JdbcTemplate jdbc;

    public Settlements(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Run run(String runKey, String sellerId, String actor, Instant now) {
        if (runKey == null || !KEY.matcher(runKey).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required (8-64 characters: letters, digits, _ or -).");
        }
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtext('bazaar.settlement'))");
        boolean known = !jdbc.queryForList("SELECT 1 FROM settlement_run WHERE run_key = ?", runKey).isEmpty();
        if (known) {
            return new Run(runKey, true, jdbc.query(
                    "SELECT * FROM settlement WHERE starts_with(run_key, ?) ORDER BY seller_id, currency",
                    (rs, n) -> view(rs), runKey + ":"));
        }
        jdbc.update("INSERT INTO settlement_run (run_key, created_by, created_at) VALUES (?, ?, ?)",
                runKey, actor, Timestamp.from(now));

        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.seller_id, e.currency, e.amount
                FROM ledger_entry e
                JOIN fulfillment f ON f.order_id = e.order_id AND f.seller_id = e.seller_id
                WHERE e.account = 'SELLER_PAYABLE' AND f.status = 'DELIVERED'
                  AND NOT EXISTS (SELECT 1 FROM settlement_entry s WHERE s.ledger_entry_id = e.id)
                """);
        List<Object> args = new ArrayList<>();
        if (sellerId != null) {
            sql.append(" AND e.seller_id = ?");
            args.add(sellerId);
        }
        sql.append(" ORDER BY e.seller_id, e.currency, e.created_at, e.id");
        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        jdbc.query(sql.toString(), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            Candidate candidate = new Candidate(rs.getObject("id", UUID.class), rs.getString("seller_id"),
                    rs.getString("currency").trim(), rs.getBigDecimal("amount"));
            groups.computeIfAbsent(candidate.sellerId() + "|" + candidate.currency(), k -> new ArrayList<>())
                    .add(candidate);
        }, args.toArray());

        List<SettlementView> created = new ArrayList<>();
        for (List<Candidate> group : groups.values()) {
            BigDecimal net = group.stream().map(Candidate::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (net.signum() <= 0) {
                continue; // negative or zero balances carry forward to a later run
            }
            Candidate first = group.get(0);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO settlement (id, seller_id, currency, amount, entry_count, status, run_key, created_by,
                                            created_at)
                    VALUES (?, ?, ?, ?, ?, 'MOCK_PAID', ?, ?, ?)
                    """, id, first.sellerId(), first.currency(), net, group.size(),
                    runKey + ":" + first.sellerId() + ":" + first.currency(), actor, Timestamp.from(now));
            jdbc.batchUpdate("INSERT INTO settlement_entry (settlement_id, ledger_entry_id) VALUES (?, ?)",
                    group, group.size(), (statement, candidate) -> {
                        statement.setObject(1, id);
                        statement.setObject(2, candidate.id());
                    });
            created.add(new SettlementView(id, first.sellerId(), first.currency(), net, group.size(), "MOCK_PAID",
                    actor, now));
        }
        return new Run(runKey, false, created);
    }

    @Transactional(readOnly = true)
    public List<SettlementView> list() {
        return jdbc.query("SELECT * FROM settlement ORDER BY created_at DESC, id LIMIT 100", (rs, n) -> view(rs));
    }

    static List<SettlementView> listFor(JdbcTemplate jdbc, String sellerId) {
        return jdbc.query("SELECT * FROM settlement WHERE seller_id = ? ORDER BY created_at DESC, id LIMIT 20",
                (rs, n) -> view(rs), sellerId);
    }

    private static SettlementView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SettlementView(
                rs.getObject("id", UUID.class),
                rs.getString("seller_id"),
                rs.getString("currency").trim(),
                rs.getBigDecimal("amount"),
                rs.getInt("entry_count"),
                rs.getString("status"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    private record Candidate(UUID id, String sellerId, String currency, BigDecimal amount) {
    }

    public record SettlementView(
            UUID id,
            String sellerId,
            String currency,
            BigDecimal amount,
            int entryCount,
            String status,
            String createdBy,
            Instant createdAt) {
    }

    public record Run(String runKey, boolean replayed, List<SettlementView> settlements) {
    }
}

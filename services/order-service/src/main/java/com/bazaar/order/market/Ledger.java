package com.bazaar.order.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Append-only marketplace ledger (mock accounting, no real money).
 *
 * <p>Per (order, seller) group the entries always net to what the customer paid for that
 * seller's lines: {@code gross - discount}. The commission only moves value from
 * SELLER_PAYABLE to PLATFORM_REVENUE; a platform-funded discount is charged to PLATFORM_REVENUE.
 * Corrections are REVERSAL entries that negate an earlier entry; nothing is ever updated.
 */
@Service
public class Ledger {

    private static final String SELLER = "SELLER_PAYABLE";
    private static final String PLATFORM = "PLATFORM_REVENUE";

    private final JdbcTemplate jdbc;
    private final int defaultBps;

    public Ledger(JdbcTemplate jdbc, @Value("${bazaar.order.commission-default-bps:1000}") int defaultBps) {
        this.jdbc = jdbc;
        this.defaultBps = defaultBps;
    }

    /** Commission = base * bps / 10000, rounded HALF_UP to cents. */
    public static BigDecimal commission(BigDecimal base, int bps) {
        return base.multiply(BigDecimal.valueOf(bps)).divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);
    }

    /**
     * Records the sale entries of a confirmed order, one group per seller. Idempotent: every
     * entry has a deterministic key, so a replay inserts nothing.
     */
    @Transactional
    public int recordSale(UUID orderId, Instant now) {
        String currency = jdbc.queryForObject(
                "SELECT currency FROM customer_order WHERE id = ?", String.class, orderId).trim();
        String fundedBy = jdbc.query(
                "SELECT funded_by FROM coupon_redemption WHERE order_id = ? AND status = 'APPLIED'",
                (rs, n) -> rs.getString(1), orderId).stream().findFirst().orElse(null);
        List<Group> groups = jdbc.query("""
                SELECT seller_id, SUM(line_total) AS gross, SUM(discount_amount) AS discount
                FROM order_item WHERE order_id = ? GROUP BY seller_id ORDER BY seller_id
                """, (rs, n) -> new Group(rs.getString("seller_id"), rs.getBigDecimal("gross"),
                        rs.getBigDecimal("discount")), orderId);
        int inserted = 0;
        for (Group group : groups) {
            boolean sellerFunded = "SELLER".equals(fundedBy);
            BigDecimal discount = group.discount();
            BigDecimal base = sellerFunded ? group.gross().subtract(discount) : group.gross();
            int bps = rateAt(group.sellerId(), now);
            BigDecimal commission = commission(base, bps);
            String suffix = orderId + ":" + group.sellerId();
            inserted += insert(orderId, group.sellerId(), SELLER, "SALE", currency, group.gross(), null, null,
                    "SALE:" + suffix, null, "system", now);
            if (sellerFunded) {
                inserted += insert(orderId, group.sellerId(), SELLER, "DISCOUNT", currency, discount.negate(), null,
                        null, "DISCOUNT:" + suffix, "Seller-funded coupon", "system", now);
            }
            inserted += insert(orderId, group.sellerId(), SELLER, "COMMISSION", currency, commission.negate(), bps,
                    null, "COMMISSION_SELLER:" + suffix, null, "system", now);
            inserted += insert(orderId, group.sellerId(), PLATFORM, "COMMISSION", currency, commission, bps, null,
                    "COMMISSION_PLATFORM:" + suffix, null, "system", now);
            if (!sellerFunded && discount.signum() > 0) {
                inserted += insert(orderId, group.sellerId(), PLATFORM, "PLATFORM_DISCOUNT", currency,
                        discount.negate(), null, null, "PLATFORM_DISCOUNT:" + suffix, "Platform-funded coupon",
                        "system", now);
            }
        }
        return inserted;
    }

    /** Negates every not-yet-reversed entry of a group. Returns how many reversals were written. */
    @Transactional
    public int reverseGroup(UUID orderId, String sellerId, String reason, String actor, Instant now) {
        List<LedgerEntryView> open = jdbc.query("""
                SELECT e.* FROM ledger_entry e
                WHERE e.order_id = ? AND e.seller_id = ? AND e.entry_type <> 'REVERSAL'
                  AND NOT EXISTS (SELECT 1 FROM ledger_entry r WHERE r.reverses_entry_id = e.id)
                ORDER BY e.created_at, e.id
                """, this::entry, orderId, sellerId);
        int written = 0;
        for (LedgerEntryView original : open) {
            written += insert(orderId, sellerId, original.account(), "REVERSAL", original.currency(),
                    original.amount().negate(), original.rateBps(), original.id(),
                    "REVERSAL:" + original.id(), reason, actor, now);
        }
        return written;
    }

    /**
     * Admin refund of a shipped or delivered group: ledger adjustment only. The payment itself
     * is not refunded (payment-service has no partial refund) and stock is not restocked.
     */
    @Transactional
    public int refund(UUID orderId, String sellerId, String reason, String actor, Instant now) {
        String status = jdbc.query("""
                SELECT status FROM fulfillment WHERE order_id = ? AND seller_id = ? FOR UPDATE
                """, (rs, n) -> rs.getString(1), orderId, sellerId).stream().findFirst().orElse(null);
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fulfillment group not found.");
        }
        if (!"SHIPPED".equals(status) && !"DELIVERED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only shipped or delivered groups are refunded; use fulfillment cancellation before shipping.");
        }
        Integer entries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE order_id = ? AND seller_id = ?",
                Integer.class, orderId, sellerId);
        if (entries == null || entries == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No ledger entries for this group.");
        }
        return reverseGroup(orderId, sellerId, reason, actor, now);
    }

    public int rateAt(String sellerId, Instant at) {
        return jdbc.query("""
                SELECT rate_bps FROM commission_rate
                WHERE seller_id = ? AND effective_from <= ?
                ORDER BY effective_from DESC, created_at DESC LIMIT 1
                """, (rs, n) -> rs.getInt(1), sellerId, Timestamp.from(at)).stream().findFirst().orElse(defaultBps);
    }

    public int defaultBps() {
        return defaultBps;
    }

    @Transactional
    public RateView setRate(String sellerId, int bps, String actor, Instant now) {
        if (bps < 0 || bps > 3000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rateBps must be between 0 and 3000.");
        }
        jdbc.update("""
                INSERT INTO commission_rate (id, seller_id, rate_bps, effective_from, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), sellerId, bps, Timestamp.from(now), actor, Timestamp.from(now));
        return new RateView(sellerId, bps, now, actor);
    }

    @Transactional(readOnly = true)
    public List<RateView> rates() {
        return jdbc.query("""
                SELECT seller_id, rate_bps, effective_from, created_by FROM commission_rate
                ORDER BY effective_from DESC, created_at DESC LIMIT 100
                """, (rs, n) -> new RateView(rs.getString("seller_id"), rs.getInt("rate_bps"),
                        rs.getTimestamp("effective_from").toInstant(), rs.getString("created_by")));
    }

    @Transactional(readOnly = true)
    public SellerFinance sellerFinance(String sellerId, Instant now) {
        List<Balance> balances = jdbc.query("""
                SELECT e.currency,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.entry_type = 'SALE'), 0) AS sales,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.entry_type = 'DISCOUNT'), 0) AS discounts,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.entry_type = 'COMMISSION'), 0) AS commission,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.entry_type = 'REVERSAL'), 0) AS reversals,
                       COALESCE(SUM(e.amount), 0) AS net,
                       COALESCE(SUM(e.amount) FILTER (WHERE s.ledger_entry_id IS NOT NULL), 0) AS settled,
                       COALESCE(SUM(e.amount) FILTER (WHERE s.ledger_entry_id IS NULL AND f.status = 'DELIVERED'), 0)
                           AS eligible
                FROM ledger_entry e
                LEFT JOIN settlement_entry s ON s.ledger_entry_id = e.id
                LEFT JOIN fulfillment f ON f.order_id = e.order_id AND f.seller_id = e.seller_id
                WHERE e.seller_id = ? AND e.account = 'SELLER_PAYABLE'
                GROUP BY e.currency ORDER BY e.currency
                """, (rs, n) -> {
                    BigDecimal net = rs.getBigDecimal("net");
                    BigDecimal settled = rs.getBigDecimal("settled");
                    return new Balance(rs.getString("currency").trim(), rs.getBigDecimal("sales"),
                            rs.getBigDecimal("discounts"), rs.getBigDecimal("commission"),
                            rs.getBigDecimal("reversals"), net, settled, net.subtract(settled),
                            rs.getBigDecimal("eligible"));
                }, sellerId);
        List<LedgerEntryView> entries = jdbc.query("""
                SELECT * FROM ledger_entry WHERE seller_id = ? AND account = 'SELLER_PAYABLE'
                ORDER BY created_at DESC, id LIMIT 50
                """, this::entry, sellerId);
        return new SellerFinance(rateAt(sellerId, now), balances, entries, Settlements.listFor(jdbc, sellerId));
    }

    @Transactional(readOnly = true)
    public LedgerPage entries(UUID orderId, String sellerId, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (orderId != null) {
            where.append(" AND order_id = ?");
            args.add(orderId);
        }
        if (sellerId != null) {
            where.append(" AND seller_id = ?");
            args.add(sellerId);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        List<LedgerEntryView> items = jdbc.query(
                "SELECT * FROM ledger_entry" + where + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                this::entry, pageArgs.toArray());
        long count = total == null ? 0 : total;
        return new LedgerPage(items, safePage, safeSize, count, (int) ((count + safeSize - 1) / safeSize));
    }

    /**
     * Full-scan reconciliation. Each (order, seller) group must net to {@code gross - discount}
     * (or zero once reversed); each settlement must equal its linked entries.
     */
    @Transactional(readOnly = true)
    public Reconciliation reconcile() {
        List<GroupCheck> groups = jdbc.query("""
                SELECT g.order_id, g.seller_id, g.net, g.reversed,
                       (SELECT COALESCE(SUM(i.line_total - i.discount_amount), 0) FROM order_item i
                        WHERE i.order_id = g.order_id AND i.seller_id = g.seller_id) AS expected
                FROM (SELECT order_id, seller_id, SUM(amount) AS net,
                             BOOL_OR(entry_type = 'REVERSAL') AS reversed
                      FROM ledger_entry GROUP BY order_id, seller_id) g
                """, (rs, n) -> {
                    boolean reversed = rs.getBoolean("reversed");
                    BigDecimal expected = reversed ? BigDecimal.ZERO : rs.getBigDecimal("expected");
                    return new GroupCheck(rs.getObject("order_id", UUID.class), rs.getString("seller_id"),
                            rs.getBigDecimal("net"), expected);
                });
        List<GroupCheck> mismatches = groups.stream()
                .filter(group -> group.ledgerNet().compareTo(group.expected()) != 0)
                .toList();
        List<SettlementCheck> settlementMismatches = jdbc.query("""
                SELECT s.id, s.amount, s.entry_count, COALESCE(SUM(e.amount), 0) AS linked, COUNT(e.id) AS linked_count
                FROM settlement s
                LEFT JOIN settlement_entry se ON se.settlement_id = s.id
                LEFT JOIN ledger_entry e ON e.id = se.ledger_entry_id
                GROUP BY s.id, s.amount, s.entry_count
                HAVING s.amount <> COALESCE(SUM(e.amount), 0) OR s.entry_count <> COUNT(e.id)
                """, (rs, n) -> new SettlementCheck(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("linked"), rs.getInt("entry_count"), rs.getInt("linked_count")));
        Integer withoutLedger = jdbc.queryForObject("""
                SELECT COUNT(*) FROM fulfillment f
                WHERE NOT EXISTS (SELECT 1 FROM ledger_entry e WHERE e.order_id = f.order_id AND e.seller_id = f.seller_id)
                """, Integer.class);
        List<AccountTotal> totals = jdbc.query("""
                SELECT currency, account, SUM(amount) AS total FROM ledger_entry
                GROUP BY currency, account ORDER BY currency, account
                """, (rs, n) -> new AccountTotal(rs.getString("currency").trim(), rs.getString("account"),
                        rs.getBigDecimal("total")));
        boolean balanced = mismatches.isEmpty() && settlementMismatches.isEmpty();
        return new Reconciliation(groups.size(), balanced, mismatches, settlementMismatches,
                withoutLedger == null ? 0 : withoutLedger, totals);
    }

    private int insert(
            UUID orderId, String sellerId, String account, String type, String currency, BigDecimal amount,
            Integer bps, UUID reverses, String key, String reason, String actor, Instant now) {
        if (amount.signum() == 0) {
            return 0;
        }
        return jdbc.update("""
                INSERT INTO ledger_entry (id, order_id, seller_id, account, entry_type, currency, amount, rate_bps,
                                          reverses_entry_id, idempotency_key, reason, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), orderId, sellerId, account, type, currency, amount, bps, reverses, key,
                reason, actor, Timestamp.from(now));
    }

    private LedgerEntryView entry(ResultSet rs, int row) throws SQLException {
        return new LedgerEntryView(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("seller_id"),
                rs.getString("account"),
                rs.getString("entry_type"),
                rs.getString("currency").trim(),
                rs.getBigDecimal("amount"),
                rs.getObject("rate_bps", Integer.class),
                rs.getObject("reverses_entry_id", UUID.class),
                rs.getString("reason"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    private record Group(String sellerId, BigDecimal gross, BigDecimal discount) {
    }

    public record RateView(String sellerId, int rateBps, Instant effectiveFrom, String createdBy) {
    }

    public record Balance(
            String currency,
            BigDecimal sales,
            BigDecimal discounts,
            BigDecimal commission,
            BigDecimal reversals,
            BigDecimal netPayable,
            BigDecimal settled,
            BigDecimal outstanding,
            BigDecimal eligibleForSettlement) {
    }

    public record SellerFinance(
            int commissionRateBps,
            List<Balance> balances,
            List<LedgerEntryView> entries,
            List<Settlements.SettlementView> settlements) {
    }

    public record LedgerPage(List<LedgerEntryView> items, int page, int size, long total, int pages) {
    }

    public record GroupCheck(UUID orderId, String sellerId, BigDecimal ledgerNet, BigDecimal expected) {
    }

    public record SettlementCheck(
            UUID settlementId, BigDecimal amount, BigDecimal linkedAmount, int entryCount, int linkedCount) {
    }

    public record AccountTotal(String currency, String account, BigDecimal total) {
    }

    public record Reconciliation(
            int groupsChecked,
            boolean balanced,
            List<GroupCheck> groupMismatches,
            List<SettlementCheck> settlementMismatches,
            int fulfillmentGroupsWithoutLedger,
            List<AccountTotal> totals) {
    }
}

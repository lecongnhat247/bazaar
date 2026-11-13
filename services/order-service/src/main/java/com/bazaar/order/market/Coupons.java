package com.bazaar.order.market;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Coupons {

    static final Pattern CODE = Pattern.compile("[A-Z0-9_-]{3,40}");

    private static final String COLUMNS = """
            code, discount_type, percent, fixed_amount, currency, min_subtotal, max_discount, seller_id,
            funded_by, starts_at, ends_at, usage_limit, per_customer_limit, status
            """;

    private final JdbcTemplate jdbc;

    public Coupons(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Coupon is not valid.");
        }
        return code;
    }

    /**
     * Evaluates a coupon for a checkout inside the checkout transaction. The coupon row is
     * locked so usage limits cannot be exceeded by concurrent checkouts.
     */
    @Transactional
    public Allocation apply(
            String rawCode, String customerId, String currency, List<PricedLine> lines,
            boolean campaignCheckout, Instant now) {
        String code = normalize(rawCode);
        if (code == null) {
            return Allocation.none(lines.size());
        }
        List<CouponTerms> found = jdbc.query(
                "SELECT " + COLUMNS + " FROM coupon WHERE code = ? FOR UPDATE", this::terms, code);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Coupon is not valid.");
        }
        Integer used = jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupon_redemption WHERE coupon_code = ? AND status = 'APPLIED'",
                Integer.class, code);
        Integer usedByCustomer = jdbc.queryForObject("""
                SELECT COUNT(*) FROM coupon_redemption
                WHERE coupon_code = ? AND customer_id = ? AND status = 'APPLIED'
                """, Integer.class, code, customerId);
        return CouponRules.evaluate(found.get(0), currency, lines, campaignCheckout, now,
                used == null ? 0 : used, usedByCustomer == null ? 0 : usedByCustomer);
    }

    @Transactional
    public void redeem(UUID orderId, String customerId, Allocation allocation, Instant now) {
        if (!allocation.applied()) {
            return;
        }
        jdbc.update("""
                INSERT INTO coupon_redemption (id, coupon_code, order_id, customer_id, discount_amount, funded_by,
                                               status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'APPLIED', ?)
                """, UUID.randomUUID(), allocation.code(), orderId, customerId, allocation.total(),
                allocation.fundedBy(), Timestamp.from(now));
    }

    /** Frees the usage of an order that will never confirm. Safe to call repeatedly. */
    @Transactional
    public void release(UUID orderId, Instant now) {
        jdbc.update("""
                UPDATE coupon_redemption SET status = 'RELEASED', released_at = ?
                WHERE order_id = ? AND status = 'APPLIED'
                """, Timestamp.from(now), orderId);
    }

    @Transactional
    public CouponView create(String actor, NewCoupon request, Instant now) {
        String code = normalize(request.code());
        if (code == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coupon code is required.");
        }
        boolean percent = "PERCENT".equals(request.discountType());
        boolean fixed = "FIXED".equals(request.discountType());
        if (!percent && !fixed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "discountType must be PERCENT or FIXED.");
        }
        if (percent && (request.percent() == null || request.percent().signum() <= 0
                || request.percent().compareTo(BigDecimal.valueOf(90)) > 0 || request.fixedAmount() != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A PERCENT coupon needs percent in (0, 90].");
        }
        if (fixed && (request.fixedAmount() == null || request.fixedAmount().signum() <= 0
                || request.percent() != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A FIXED coupon needs a positive fixedAmount.");
        }
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endsAt must be after startsAt.");
        }
        String sellerId = request.sellerId() == null || request.sellerId().isBlank() ? null : request.sellerId().trim();
        String fundedBy = request.fundedBy() != null ? request.fundedBy() : (sellerId == null ? "PLATFORM" : "SELLER");
        if ("SELLER".equals(fundedBy) && sellerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A seller-funded coupon needs a sellerId.");
        }
        try {
            jdbc.update("""
                    INSERT INTO coupon (code, discount_type, percent, fixed_amount, currency, min_subtotal, max_discount,
                                        seller_id, funded_by, starts_at, ends_at, usage_limit, per_customer_limit,
                                        status, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                    """,
                    code, request.discountType(), request.percent(), request.fixedAmount(), request.currency(),
                    request.minSubtotal() == null ? BigDecimal.ZERO : request.minSubtotal(), request.maxDiscount(),
                    sellerId, fundedBy, Timestamp.from(request.startsAt()), Timestamp.from(request.endsAt()),
                    request.usageLimit(), request.perCustomerLimit() == null ? 1 : request.perCustomerLimit(),
                    actor, Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Coupon code already exists.");
        }
        return view(code);
    }

    @Transactional
    public CouponView setStatus(String code, String status, Instant now) {
        String normalized = normalize(code);
        int changed = jdbc.update("UPDATE coupon SET status = ?, updated_at = ? WHERE code = ?",
                status, Timestamp.from(now), normalized);
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found.");
        }
        return view(normalized);
    }

    @Transactional(readOnly = true)
    public List<CouponView> list() {
        return jdbc.query("""
                SELECT c.*, (SELECT COUNT(*) FROM coupon_redemption r
                             WHERE r.coupon_code = c.code AND r.status = 'APPLIED') AS used
                FROM coupon c
                ORDER BY c.created_at DESC
                LIMIT 100
                """, this::viewRow);
    }

    private CouponView view(String code) {
        return jdbc.query("""
                SELECT c.*, (SELECT COUNT(*) FROM coupon_redemption r
                             WHERE r.coupon_code = c.code AND r.status = 'APPLIED') AS used
                FROM coupon c WHERE c.code = ?
                """, this::viewRow, code).get(0);
    }

    private CouponView viewRow(ResultSet rs, int row) throws SQLException {
        return new CouponView(terms(rs, row), rs.getInt("used"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private CouponTerms terms(ResultSet rs, int row) throws SQLException {
        Integer limit = rs.getObject("usage_limit", Integer.class);
        return new CouponTerms(
                rs.getString("code"),
                rs.getString("discount_type"),
                rs.getBigDecimal("percent"),
                rs.getBigDecimal("fixed_amount"),
                rs.getString("currency").trim(),
                rs.getBigDecimal("min_subtotal"),
                rs.getBigDecimal("max_discount"),
                rs.getString("seller_id"),
                rs.getString("funded_by"),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),
                limit,
                rs.getInt("per_customer_limit"),
                rs.getString("status"));
    }

    public record NewCoupon(
            String code,
            String discountType,
            BigDecimal percent,
            BigDecimal fixedAmount,
            String currency,
            BigDecimal minSubtotal,
            BigDecimal maxDiscount,
            String sellerId,
            String fundedBy,
            Instant startsAt,
            Instant endsAt,
            Integer usageLimit,
            Integer perCustomerLimit) {
    }
}

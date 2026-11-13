package com.bazaar.inventory.stock;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.inventory.api.CampaignReconcile;
import com.bazaar.inventory.api.CampaignResponse;

@Service
class FlashCampaigns {

    private final JdbcTemplate jdbc;
    private final InventoryMetrics metrics;

    FlashCampaigns(JdbcTemplate jdbc, InventoryMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    CampaignResponse create(
            InventoryAccess access,
            UUID productId,
            BigDecimal price,
            String currency,
            int quota,
            int perCustomerLimit,
            Instant startsAt,
            Instant endsAt,
            Instant now) {
        if (!access.seller()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a seller can create a campaign.");
        }
        if (!startsAt.isBefore(endsAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The campaign window is invalid.");
        }
        String seller = owner(productId);
        if (!access.admin() && !seller.equals(access.subject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot sell this product.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO flash_campaign (
                    id, seller_id, product_id, price, currency, status, quota, remaining,
                    per_customer_limit, starts_at, ends_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?)
                """, id, seller, productId, price, currency, quota, quota, perCustomerLimit,
                Timestamp.from(startsAt), Timestamp.from(endsAt), Timestamp.from(now), Timestamp.from(now));
        return get(id);
    }

    CampaignResponse schedule(InventoryAccess access, UUID id, Instant now) {
        int changed = jdbc.update("""
                UPDATE flash_campaign
                SET status = CASE WHEN starts_at <= ? AND ends_at > ? THEN 'ACTIVE' ELSE 'SCHEDULED' END,
                    updated_at = ?
                WHERE id = ? AND status = 'DRAFT' AND ends_at > ? AND (seller_id = ? OR ? = TRUE)
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), id, Timestamp.from(now),
                access.subject(), access.admin());
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The campaign cannot be scheduled.");
        }
        return get(id);
    }

    void markCancelled(InventoryAccess access, UUID id, Instant now) {
        int changed = jdbc.update("""
                UPDATE flash_campaign
                SET status = 'CANCELLED', updated_at = ?
                WHERE id = ? AND status IN ('DRAFT', 'SCHEDULED', 'ACTIVE') AND (seller_id = ? OR ? = TRUE)
                """, Timestamp.from(now), id, access.subject(), access.admin());
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The campaign cannot be cancelled.");
        }
    }

    List<CampaignResponse> list(String sellerId, Instant now) {
        jdbc.update("""
                UPDATE flash_campaign
                SET status = 'ENDED', updated_at = ?
                WHERE seller_id = ? AND status IN ('SCHEDULED', 'ACTIVE') AND ends_at <= ?
                """, Timestamp.from(now), sellerId, Timestamp.from(now));
        jdbc.update("""
                UPDATE flash_campaign
                SET status = 'ACTIVE', updated_at = ?
                WHERE seller_id = ? AND status = 'SCHEDULED' AND starts_at <= ? AND ends_at > ?
                """, Timestamp.from(now), sellerId, Timestamp.from(now), Timestamp.from(now));
        return jdbc.query("""
                SELECT id, seller_id, product_id, price, currency, status, quota, remaining,
                       per_customer_limit, starts_at, ends_at
                FROM flash_campaign
                WHERE seller_id = ?
                ORDER BY starts_at DESC
                LIMIT 50
                """, this::row, sellerId);
    }

    List<CampaignResponse> monitor(Instant now) {
        jdbc.update("""
                UPDATE flash_campaign
                SET status = 'ENDED', updated_at = ?
                WHERE status IN ('SCHEDULED', 'ACTIVE') AND ends_at <= ?
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                UPDATE flash_campaign
                SET status = 'ACTIVE', updated_at = ?
                WHERE status = 'SCHEDULED' AND starts_at <= ? AND ends_at > ?
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return jdbc.query("""
                SELECT id, seller_id, product_id, price, currency, status, quota, remaining,
                       per_customer_limit, starts_at, ends_at
                FROM flash_campaign
                ORDER BY starts_at DESC
                LIMIT 50
                """, this::row);
    }

    CampaignResponse visible(InventoryAccess access, UUID id, Instant now) {
        promote(id, now);
        CampaignResponse campaign = get(id);
        if (("DRAFT".equals(campaign.status()) || "CANCELLED".equals(campaign.status()))
                && !access.admin()
                && !campaign.sellerId().equals(access.subject())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign was not found.");
        }
        return campaign;
    }

    void consume(UUID campaignId, UUID productId, String customerId, int quantity, Instant now) {
        promote(campaignId, now);
        List<Integer> limits = jdbc.query("""
                UPDATE flash_campaign
                SET remaining = remaining - ?, updated_at = ?
                WHERE id = ? AND product_id = ? AND status = 'ACTIVE' AND remaining >= ?
                  AND starts_at <= ? AND ends_at > ?
                RETURNING per_customer_limit
                """, (rs, row) -> rs.getInt(1),
                quantity, Timestamp.from(now), campaignId, productId, quantity,
                Timestamp.from(now), Timestamp.from(now));
        if (limits.isEmpty()) {
            metrics.flashRejected();
            throw new StockShortException("The campaign cannot take this quantity.");
        }
        int taken = jdbc.update("""
                INSERT INTO flash_hold (campaign_id, customer_id, held, committed)
                SELECT ?, ?, ?, 0
                WHERE ? <= ?
                ON CONFLICT (campaign_id, customer_id)
                DO UPDATE SET held = flash_hold.held + EXCLUDED.held
                WHERE flash_hold.held + flash_hold.committed + EXCLUDED.held <= ?
                """, campaignId, customerId, quantity, quantity, limits.getFirst(), limits.getFirst());
        if (taken != 1) {
            metrics.flashRejected();
            throw new StockShortException("The customer has reached the campaign limit.");
        }
        metrics.flashReserved();
    }

    void restore(UUID campaignId, String customerId, int quantity, Instant now) {
        int returned = jdbc.update("""
                UPDATE flash_campaign
                SET remaining = remaining + ?, updated_at = ?
                WHERE id = ? AND remaining + ? <= quota
                """, quantity, Timestamp.from(now), campaignId, quantity);
        if (returned != 1) {
            throw new StockShortException("Campaign quota could not be restored.");
        }
        int released = jdbc.update("""
                UPDATE flash_hold
                SET held = held - ?
                WHERE campaign_id = ? AND customer_id = ? AND held >= ?
                """, quantity, campaignId, customerId, quantity);
        if (released != 1) {
            throw new StockShortException("Campaign hold could not be restored.");
        }
    }

    void commitHold(UUID campaignId, String customerId, int quantity) {
        int changed = jdbc.update("""
                UPDATE flash_hold
                SET held = held - ?, committed = committed + ?
                WHERE campaign_id = ? AND customer_id = ? AND held >= ?
                """, quantity, quantity, campaignId, customerId, quantity);
        if (changed != 1) {
            throw new StockShortException("The campaign hold could not be committed.");
        }
    }

    CampaignReconcile reconcile(InventoryAccess access, UUID id) {
        CampaignResponse campaign = get(id);
        if (!access.admin() && !campaign.sellerId().equals(access.subject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot reconcile this campaign.");
        }
        Counts counts = counts(id);
        int accounted = counts.remaining() + counts.held() + counts.committed();
        if (accounted > counts.quota()) {
            int corrected = counts.quota() - counts.held() - counts.committed();
            jdbc.update("UPDATE flash_campaign SET remaining = ? WHERE id = ? AND remaining > ?", corrected, id, corrected);
            counts = counts(id);
            accounted = counts.remaining() + counts.held() + counts.committed();
        }
        return new CampaignReconcile(counts.quota(), counts.remaining(), counts.held(), counts.committed(), accounted == counts.quota());
    }

    private void promote(UUID id, Instant now) {
        jdbc.update("""
                UPDATE flash_campaign
                SET status = 'ENDED', updated_at = ?
                WHERE id = ? AND status IN ('SCHEDULED', 'ACTIVE') AND ends_at <= ?
                """, Timestamp.from(now), id, Timestamp.from(now));
        jdbc.update("""
                UPDATE flash_campaign
                SET status = 'ACTIVE', updated_at = ?
                WHERE id = ? AND status = 'SCHEDULED' AND starts_at <= ? AND ends_at > ?
                """, Timestamp.from(now), id, Timestamp.from(now), Timestamp.from(now));
    }

    private String owner(UUID productId) {
        List<String> sellers = jdbc.query(
                "SELECT seller_id FROM inventory_item WHERE product_id = ?",
                (rs, row) -> rs.getString("seller_id"),
                productId);
        if (sellers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock was not found.");
        }
        return sellers.getFirst();
    }

    private CampaignResponse get(UUID id) {
        List<CampaignResponse> rows = jdbc.query("""
                SELECT id, seller_id, product_id, price, currency, status, quota, remaining,
                       per_customer_limit, starts_at, ends_at
                FROM flash_campaign
                WHERE id = ?
                """, this::row, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign was not found.");
        }
        return rows.getFirst();
    }

    private CampaignResponse row(ResultSet rs, int ignored) throws SQLException {
        return new CampaignResponse(
                rs.getObject("id", UUID.class),
                rs.getString("seller_id"),
                rs.getObject("product_id", UUID.class),
                rs.getBigDecimal("price"),
                rs.getString("currency").trim(),
                rs.getString("status"),
                rs.getInt("quota"),
                rs.getInt("remaining"),
                rs.getInt("per_customer_limit"),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant());
    }

    private Counts counts(UUID id) {
        return jdbc.queryForObject("""
                SELECT c.quota, c.remaining,
                       COALESCE(SUM(h.held), 0),
                       COALESCE(SUM(h.committed), 0)
                FROM flash_campaign c
                LEFT JOIN flash_hold h ON h.campaign_id = c.id
                WHERE c.id = ?
                GROUP BY c.quota, c.remaining
                """, (rs, row) -> new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)), id);
    }

    private record Counts(int quota, int remaining, int held, int committed) {
    }
}

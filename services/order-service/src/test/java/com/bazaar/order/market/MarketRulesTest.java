package com.bazaar.order.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Pure rule tests: no Spring context, no database. */
class MarketRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    private static CouponTerms percent(String percent, String sellerId, String max, String min) {
        return new CouponTerms("P", "PERCENT", new BigDecimal(percent), null, "USD", money(min),
                max == null ? null : money(max), sellerId, sellerId == null ? "PLATFORM" : "SELLER",
                NOW.minusSeconds(3600), NOW.plusSeconds(3600), null, 1, "ACTIVE");
    }

    private static CouponTerms fixed(String amount, String sellerId) {
        return new CouponTerms("F", "FIXED", null, new BigDecimal(amount), "USD", money("0"), null, sellerId,
                sellerId == null ? "PLATFORM" : "SELLER", NOW.minusSeconds(3600), NOW.plusSeconds(3600), null, 1,
                "ACTIVE");
    }

    private static List<PricedLine> cart(String... sellerAndTotal) {
        java.util.ArrayList<PricedLine> lines = new java.util.ArrayList<>();
        for (int i = 0; i < sellerAndTotal.length; i += 2) {
            lines.add(new PricedLine(sellerAndTotal[i], money(sellerAndTotal[i + 1])));
        }
        return lines;
    }

    @Test
    void percentCouponIsRoundedHalfUpToCents() {
        Allocation allocation = CouponRules.evaluate(percent("12.5", null, null, "0"), "USD",
                cart("s1", "19.99"), false, NOW, 0, 0);
        // 19.99 * 12.5% = 2.49875 -> 2.50
        assertThat(allocation.total()).isEqualByComparingTo("2.50");
        assertThat(allocation.perLine()).containsExactly(money("2.50"));
    }

    @Test
    void allocationAlwaysSumsToTheDiscountAndNeverExceedsALine() {
        Allocation allocation = CouponRules.evaluate(fixed("5.00", null), "USD",
                cart("s1", "25.00", "s2", "19.50", "s3", "0.01"), false, NOW, 0, 0);
        assertThat(allocation.total()).isEqualByComparingTo("5.00");
        BigDecimal sum = allocation.perLine().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("5.00");
        assertThat(allocation.perLine().get(2)).isLessThanOrEqualTo(money("0.01"));
    }

    @Test
    void aSellerCouponOnlyDiscountsThatSellersLines() {
        Allocation allocation = CouponRules.evaluate(percent("10", "s2", null, "0"), "USD",
                cart("s1", "25.00", "s2", "19.50"), false, NOW, 0, 0);
        assertThat(allocation.perLine().get(0)).isEqualByComparingTo("0.00");
        assertThat(allocation.perLine().get(1)).isEqualByComparingTo("1.95");
        assertThat(allocation.fundedBy()).isEqualTo("SELLER");
    }

    @Test
    void maximumDiscountAndOrderTotalCapTheAmount() {
        Allocation capped = CouponRules.evaluate(percent("50", null, "3.00", "0"), "USD",
                cart("s1", "100.00"), false, NOW, 0, 0);
        assertThat(capped.total()).isEqualByComparingTo("3.00");

        Allocation notFree = CouponRules.evaluate(fixed("500.00", null), "USD",
                cart("s1", "10.00"), false, NOW, 0, 0);
        assertThat(notFree.total()).isEqualByComparingTo("9.99");
    }

    @Test
    void rejectsIneligibleCheckouts() {
        CouponTerms terms = percent("10", null, null, "50");
        assertRejected(() -> CouponRules.evaluate(terms, "USD", cart("s1", "49.99"), false, NOW, 0, 0), "minimum");
        assertRejected(() -> CouponRules.evaluate(terms, "EUR", cart("s1", "80.00"), false, NOW, 0, 0), "currency");
        assertRejected(() -> CouponRules.evaluate(terms, "USD", cart("s1", "80.00"), true, NOW, 0, 0), "campaign");
        assertRejected(() -> CouponRules.evaluate(terms, "USD", cart("s1", "80.00"), false,
                NOW.minusSeconds(7200), 0, 0), "not active");
        assertRejected(() -> CouponRules.evaluate(terms, "USD", cart("s1", "80.00"), false,
                NOW.plusSeconds(3600), 0, 0), "expired");
        assertRejected(() -> CouponRules.evaluate(percent("10", "other", null, "0"), "USD",
                cart("s1", "80.00"), false, NOW, 0, 0), "does not apply");
    }

    @Test
    void enforcesUsageLimits() {
        CouponTerms limited = new CouponTerms("L", "FIXED", null, money("5"), "USD", money("0"), null, null,
                "PLATFORM", NOW.minusSeconds(60), NOW.plusSeconds(60), 3, 2, "ACTIVE");
        assertThat(CouponRules.evaluate(limited, "USD", cart("s1", "30.00"), false, NOW, 2, 1).total())
                .isEqualByComparingTo("5.00");
        assertRejected(() -> CouponRules.evaluate(limited, "USD", cart("s1", "30.00"), false, NOW, 3, 0),
                "usage limit");
        assertRejected(() -> CouponRules.evaluate(limited, "USD", cart("s1", "30.00"), false, NOW, 1, 2),
                "already used");
        CouponTerms disabled = new CouponTerms("D", "FIXED", null, money("5"), "USD", money("0"), null, null,
                "PLATFORM", NOW.minusSeconds(60), NOW.plusSeconds(60), null, 1, "DISABLED");
        assertRejected(() -> CouponRules.evaluate(disabled, "USD", cart("s1", "30.00"), false, NOW, 0, 0),
                "not valid");
    }

    @Test
    void commissionIsBasisPointsRoundedHalfUp() {
        assertThat(Ledger.commission(money("19.50"), 1500)).isEqualByComparingTo("2.93"); // 2.925
        assertThat(Ledger.commission(money("25.00"), 1000)).isEqualByComparingTo("2.50");
        assertThat(Ledger.commission(money("0.05"), 1000)).isEqualByComparingTo("0.01"); // 0.005
        assertThat(Ledger.commission(money("100.00"), 0)).isEqualByComparingTo("0.00");
    }

    @Test
    void orderFulfillmentStatusIsDerivedFromTheSellerGroups() {
        assertThat(Fulfillments.summary(List.of())).isEqualTo("NONE");
        assertThat(Fulfillments.summary(List.of(group("PENDING"), group("PACKED")))).isEqualTo("PENDING");
        assertThat(Fulfillments.summary(List.of(group("SHIPPED"), group("PENDING")))).isEqualTo("PARTIALLY_SHIPPED");
        assertThat(Fulfillments.summary(List.of(group("DELIVERED"), group("PACKED")))).isEqualTo("PARTIALLY_SHIPPED");
        assertThat(Fulfillments.summary(List.of(group("SHIPPED"), group("DELIVERED")))).isEqualTo("SHIPPED");
        assertThat(Fulfillments.summary(List.of(group("DELIVERED"), group("CANCELLED")))).isEqualTo("DELIVERED");
        assertThat(Fulfillments.summary(List.of(group("CANCELLED"), group("CANCELLED")))).isEqualTo("CANCELLED");
    }

    private static FulfillmentView group(String status) {
        return new FulfillmentView(java.util.UUID.randomUUID(), "s", status, null, null, null, null, null, NOW,
                List.of());
    }

    private static void assertRejected(Runnable action, String fragment) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(fragment);
    }
}

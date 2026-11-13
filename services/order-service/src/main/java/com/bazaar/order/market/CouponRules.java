package com.bazaar.order.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure coupon rules. Everything is computed from server-owned data (terms, priced lines,
 * redemption counts). No browser-supplied amount reaches this class.
 *
 * <p>Stacking rule: one coupon per order, and a coupon cannot be combined with a campaign
 * (flash-sale) price. Amounts are handled in whole cents so allocation is exact.
 */
public final class CouponRules {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CouponRules() {
    }

    public static Allocation evaluate(
            CouponTerms terms,
            String currency,
            List<PricedLine> lines,
            boolean campaignCheckout,
            Instant now,
            int used,
            int usedByCustomer) {
        if (!"ACTIVE".equals(terms.status())) {
            throw rejected("Coupon is not valid.");
        }
        if (now.isBefore(terms.startsAt())) {
            throw rejected("Coupon is not active yet.");
        }
        if (!now.isBefore(terms.endsAt())) {
            throw rejected("Coupon has expired.");
        }
        if (campaignCheckout) {
            throw rejected("A coupon cannot be combined with a campaign price.");
        }
        if (!terms.currency().trim().equals(currency.trim())) {
            throw rejected("Coupon does not apply to this currency.");
        }
        if (terms.usageLimit() != null && used >= terms.usageLimit()) {
            throw rejected("Coupon usage limit has been reached.");
        }
        if (usedByCustomer >= terms.perCustomerLimit()) {
            throw rejected("You have already used this coupon the allowed number of times.");
        }

        long[] cents = new long[lines.size()];
        long orderCents = 0;
        long eligibleCents = 0;
        List<Integer> eligible = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            cents[i] = toCents(lines.get(i).lineTotal());
            orderCents += cents[i];
            if (terms.sellerId() == null || terms.sellerId().equals(lines.get(i).sellerId())) {
                eligible.add(i);
                eligibleCents += cents[i];
            }
        }
        if (eligible.isEmpty() || eligibleCents <= 0) {
            throw rejected("Coupon does not apply to these items.");
        }
        if (eligibleCents < toCents(terms.minSubtotal())) {
            throw rejected("Order does not reach the minimum for this coupon.");
        }

        long discount = "PERCENT".equals(terms.discountType())
                ? BigDecimal.valueOf(eligibleCents).multiply(terms.percent())
                        .divide(HUNDRED, 0, RoundingMode.HALF_UP).longValueExact()
                : toCents(terms.fixedAmount());
        if (terms.maxDiscount() != null) {
            discount = Math.min(discount, toCents(terms.maxDiscount()));
        }
        // Never discount more than the eligible items, and never make an order free.
        discount = Math.min(discount, Math.min(eligibleCents, orderCents - 1));
        if (discount <= 0) {
            throw rejected("Coupon does not apply to these items.");
        }

        long[] share = new long[lines.size()];
        long[] remainder = new long[lines.size()];
        long assigned = 0;
        for (int index : eligible) {
            long product = discount * cents[index];
            share[index] = product / eligibleCents;
            remainder[index] = product % eligibleCents;
            assigned += share[index];
        }
        long left = discount - assigned;
        List<Integer> byRemainder = new ArrayList<>(eligible);
        byRemainder.sort(Comparator.<Integer>comparingLong(i -> -remainder[i]).thenComparingInt(i -> i));
        for (int index : byRemainder) {
            if (left == 0) {
                break;
            }
            if (share[index] < cents[index]) {
                share[index]++;
                left--;
            }
        }

        List<BigDecimal> perLine = new ArrayList<>();
        for (long value : share) {
            perLine.add(BigDecimal.valueOf(value, 2));
        }
        return new Allocation(terms.code(), terms.fundedBy(), BigDecimal.valueOf(discount, 2), perLine);
    }

    private static long toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

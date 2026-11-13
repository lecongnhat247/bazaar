package com.bazaar.order.market;

import java.math.BigDecimal;
import java.time.Instant;

public record CouponTerms(
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
        int perCustomerLimit,
        String status) {
}

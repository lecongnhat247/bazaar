package com.bazaar.order.market;

import java.time.Instant;

public record CouponView(CouponTerms terms, int used, String createdBy, Instant createdAt, Instant updatedAt) {
}

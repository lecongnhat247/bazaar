package com.bazaar.order.market;

import java.math.BigDecimal;

/** A server-priced order line as seen by the coupon engine. */
public record PricedLine(String sellerId, BigDecimal lineTotal) {
}

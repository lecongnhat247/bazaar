package com.bazaar.order.api;

import jakarta.validation.constraints.Size;

/** Optional body: settle one seller only. Omit to settle every seller with a positive balance. */
public record SettlementRequest(@Size(max = 64) String sellerId) {
}

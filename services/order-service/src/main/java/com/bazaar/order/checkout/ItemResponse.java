package com.bazaar.order.checkout;

import java.math.BigDecimal;
import java.util.UUID;

public record ItemResponse(
        UUID productId,
        String sellerId,
        String sku,
        String name,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal,
        UUID reservationId,
        BigDecimal discount) {
}

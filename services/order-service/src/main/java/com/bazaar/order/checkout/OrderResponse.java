package com.bazaar.order.checkout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String customerId,
        String status,
        String currency,
        BigDecimal total,
        UUID paymentId,
        List<ItemResponse> items,
        List<HistoryResponse> history,
        Instant createdAt,
        Instant updatedAt) {
}

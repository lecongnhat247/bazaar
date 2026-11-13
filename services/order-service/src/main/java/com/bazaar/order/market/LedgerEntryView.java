package com.bazaar.order.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryView(
        UUID id,
        UUID orderId,
        String sellerId,
        String account,
        String entryType,
        String currency,
        BigDecimal amount,
        Integer rateBps,
        UUID reversesEntryId,
        String reason,
        String createdBy,
        Instant createdAt) {
}

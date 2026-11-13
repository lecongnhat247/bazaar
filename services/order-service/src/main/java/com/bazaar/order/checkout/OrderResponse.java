package com.bazaar.order.checkout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bazaar.order.market.FulfillmentView;

/**
 * {@code total} is what the customer pays (after {@code discountTotal}). Every seller of the
 * order has its own fulfillment group; {@code fulfillmentStatus} is derived from them.
 */
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
        Instant updatedAt,
        BigDecimal discountTotal,
        String couponCode,
        String fulfillmentStatus,
        List<FulfillmentView> fulfillments) {
}

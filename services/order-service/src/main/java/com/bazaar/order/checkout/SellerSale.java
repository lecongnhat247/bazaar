package com.bazaar.order.checkout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bazaar.order.market.FulfillmentView;

/** A seller's view of an order: only that seller's lines, discounts and fulfillment group. */
public record SellerSale(
        UUID id,
        String customerId,
        String status,
        String currency,
        BigDecimal sellerTotal,
        List<ItemResponse> items,
        List<HistoryResponse> history,
        Instant createdAt,
        Instant updatedAt,
        BigDecimal sellerDiscount,
        FulfillmentView fulfillment) {
}

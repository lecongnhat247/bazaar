package com.bazaar.order.checkout;

import java.math.BigDecimal;
import java.util.List;

public record SellerSalesPage(
        List<SellerSale> orders,
        int page,
        int size,
        long totalOrders,
        int totalPages,
        BigDecimal sellerTotal,
        String currency,
        long pendingOrders) {
}

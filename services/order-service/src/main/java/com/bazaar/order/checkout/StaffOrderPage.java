package com.bazaar.order.checkout;

import java.util.List;

import com.bazaar.order.checkout.OrderResponse;

public record StaffOrderPage(
        List<OrderResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {
}

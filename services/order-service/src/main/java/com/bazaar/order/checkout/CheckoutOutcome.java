package com.bazaar.order.checkout;

public record CheckoutOutcome(OrderResponse order, boolean created) {
}

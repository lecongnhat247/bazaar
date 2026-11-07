package com.bazaar.order.api;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CheckoutItem(
        @NotNull UUID productId,
        @NotNull @Min(1) @Max(100) Integer quantity) {
}

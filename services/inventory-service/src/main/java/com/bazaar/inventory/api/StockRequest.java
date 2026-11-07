package com.bazaar.inventory.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StockRequest(@NotNull @Min(0) @Max(1_000_000) Integer onHand) {
}

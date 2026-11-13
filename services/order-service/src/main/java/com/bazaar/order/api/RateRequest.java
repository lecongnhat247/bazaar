package com.bazaar.order.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RateRequest(
        @NotBlank @Size(max = 64) String sellerId,
        @NotNull @Min(0) @Max(3000) Integer rateBps) {
}

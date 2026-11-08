package com.bazaar.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CampaignRequest(
        @NotNull UUID productId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal price,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @Min(1) @Max(100000) Integer quota,
        @NotNull @Min(1) @Max(10000) Integer perCustomerLimit,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt) {
}

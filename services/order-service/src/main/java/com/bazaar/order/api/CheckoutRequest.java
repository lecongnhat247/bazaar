package com.bazaar.order.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code couponCode} is optional. Only the code is accepted; the discount is always computed by
 * the server (any extra amount field in the JSON is ignored).
 */
public record CheckoutRequest(
        @NotEmpty @Size(max = 20) List<@Valid CheckoutItem> items,
        @NotNull @Pattern(regexp = "SUCCESS|FAILURE") String outcome,
        java.util.UUID campaignId,
        @Size(max = 40) String couponCode) {
}

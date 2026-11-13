package com.bazaar.order.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RefundRequest(
        @NotNull UUID orderId,
        @NotBlank @Size(max = 64) String sellerId,
        @NotBlank @Size(max = 160) String reason) {
}

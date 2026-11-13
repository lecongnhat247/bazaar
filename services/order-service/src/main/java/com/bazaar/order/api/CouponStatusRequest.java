package com.bazaar.order.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CouponStatusRequest(@NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status) {
}

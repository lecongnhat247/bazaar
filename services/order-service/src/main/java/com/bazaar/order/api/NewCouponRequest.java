package com.bazaar.order.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.bazaar.order.market.Coupons;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NewCouponRequest(
        @NotBlank @Size(max = 40) String code,
        @NotNull @Pattern(regexp = "PERCENT|FIXED") String discountType,
        @DecimalMin("0.01") @DecimalMax("90") @Digits(integer = 2, fraction = 2) BigDecimal percent,
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal fixedAmount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal minSubtotal,
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal maxDiscount,
        @Size(max = 64) String sellerId,
        @Pattern(regexp = "PLATFORM|SELLER") String fundedBy,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @Min(1) @Max(1_000_000) Integer usageLimit,
        @Min(1) @Max(1000) Integer perCustomerLimit) {

    Coupons.NewCoupon toNewCoupon() {
        return new Coupons.NewCoupon(code, discountType, percent, fixedAmount, currency, minSubtotal, maxDiscount,
                sellerId, fundedBy, startsAt, endsAt, usageLimit, perCustomerLimit);
    }
}

package com.bazaar.payment.pay;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        String payerId,
        BigDecimal amount,
        String currency,
        String status,
        List<AttemptResponse> attempts,
        Instant createdAt,
        Instant updatedAt) {
}

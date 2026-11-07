package com.bazaar.payment.pay;

import java.time.Instant;
import java.util.UUID;

public record AttemptResponse(UUID id, String type, String resultStatus, Instant createdAt) {
}

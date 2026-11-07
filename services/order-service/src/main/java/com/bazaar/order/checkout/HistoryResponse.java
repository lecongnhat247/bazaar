package com.bazaar.order.checkout;

import java.time.Instant;

public record HistoryResponse(String status, Instant createdAt) {
}

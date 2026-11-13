package com.bazaar.payment.pay;

import java.util.List;

public record StaffPaymentPage(
        List<PaymentSummary> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {
}

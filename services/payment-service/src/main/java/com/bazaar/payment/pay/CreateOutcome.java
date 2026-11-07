package com.bazaar.payment.pay;

public record CreateOutcome(PaymentResponse payment, boolean created) {
}

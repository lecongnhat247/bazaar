package com.bazaar.payment.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaar.payment.pay.CreateOutcome;
import com.bazaar.payment.pay.PaymentResponse;
import com.bazaar.payment.pay.PaymentService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        CreateOutcome outcome = payments.create(jwt, idempotencyKey, request.orderId(), request.amount(), request.currency());
        if (outcome.created()) {
            return ResponseEntity.status(201).body(outcome.payment());
        }
        return ResponseEntity.ok(outcome.payment());
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.get(jwt, id);
    }

    @PostMapping("/{id}/simulate-success")
    public PaymentResponse succeed(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.succeed(jwt, id);
    }

    @PostMapping("/{id}/simulate-failure")
    public PaymentResponse fail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.fail(jwt, id);
    }

    @PostMapping("/{id}/cancel")
    public PaymentResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.cancel(jwt, id);
    }
}

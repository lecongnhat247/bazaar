package com.bazaar.payment.pay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentService {

    private final PaymentTransactions transactions;
    private final Clock clock;

    public PaymentService(PaymentTransactions transactions, Clock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    public CreateOutcome create(Jwt jwt, String idempotencyKey, UUID orderId, BigDecimal amount, String currency) {
        PaymentAccess access = PaymentAccess.from(jwt);
        String key = validKey(idempotencyKey);
        BigDecimal money = money(amount);
        String hash = hash(orderId, money, currency, access.subject());
        try {
            return transactions.create(access, key, hash, orderId, money, currency, clock.instant());
        } catch (DataIntegrityViolationException conflict) {
            String message = conflict.getMostSpecificCause().getMessage();
            if (message != null && message.contains("payment_idempotency_key_uq")) {
                return new CreateOutcome(transactions.replay(access, key, hash), false);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order already has a payment.");
        }
    }

    public PaymentResponse get(Jwt jwt, UUID id) {
        return transactions.get(PaymentAccess.from(jwt), id);
    }

    public StaffPaymentPage monitor(Jwt jwt, int page, int size, String query) {
        if (!PaymentAccess.from(jwt).staff()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only staff can monitor payments.");
        }
        if (page < 0 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page is invalid");
        }
        String normalized = query == null || query.isBlank() ? null : query.trim();
        if (normalized != null && normalized.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is invalid");
        }
        return transactions.monitor(page, size, normalized);
    }

    public PaymentResponse succeed(Jwt jwt, UUID id) {
        return transactions.change(PaymentAccess.from(jwt), id, "SUCCEEDED", "SIMULATE_SUCCESS", clock.instant());
    }

    public PaymentResponse fail(Jwt jwt, UUID id) {
        return transactions.change(PaymentAccess.from(jwt), id, "FAILED", "SIMULATE_FAILURE", clock.instant());
    }

    public PaymentResponse cancel(Jwt jwt, UUID id) {
        return transactions.change(PaymentAccess.from(jwt), id, "CANCELLED", "CANCEL", clock.instant());
    }

    private static String validKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is invalid");
        }
        return key;
    }

    private static BigDecimal money(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount is invalid");
        }
    }

    private static String hash(UUID orderId, BigDecimal amount, String currency, String payer) {
        try {
            String source = orderId + "|" + amount.toPlainString() + "|" + currency + "|" + payer;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}

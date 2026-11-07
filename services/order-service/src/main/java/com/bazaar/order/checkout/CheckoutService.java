package com.bazaar.order.checkout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.order.api.CheckoutItem;
import com.bazaar.order.checkout.DownstreamClient.CatalogProduct;
import com.bazaar.order.checkout.DownstreamClient.PaymentState;
import com.bazaar.order.checkout.DownstreamClient.StockHold;
import com.bazaar.order.checkout.OrderTransactions.Line;
import com.bazaar.order.checkout.OrderTransactions.OrderRow;

@Service
public class CheckoutService {

    private static final String RETRY = "Checkout could not be completed. Retry with the same Idempotency-Key.";

    private final OrderTransactions transactions;
    private final DownstreamClient downstream;
    private final Clock clock;

    public CheckoutService(OrderTransactions transactions, DownstreamClient downstream, Clock clock) {
        this.transactions = transactions;
        this.downstream = downstream;
        this.clock = clock;
    }

    public CheckoutOutcome checkout(Jwt jwt, String idempotencyKey, List<CheckoutItem> items, String outcome) {
        OrderAccess access = access(jwt);
        String key = validKey(idempotencyKey);
        List<CheckoutItem> lines = uniqueItems(items);
        String hash = hash(access.subject(), outcome, lines);
        OrderRow existing = transactions.findByKey(key);
        if (existing != null) {
            return new CheckoutOutcome(resume(access, existing, hash), false);
        }
        List<Priced> priced = price(lines);
        List<Line> reserved = reserve(key, priced);
        try {
            OrderRow created = transactions.insert(
                    access,
                    key,
                    hash,
                    outcome,
                    priced.getFirst().product().currency(),
                    total(reserved),
                    reserved,
                    clock.instant());
            return new CheckoutOutcome(finish(created), true);
        } catch (DataIntegrityViolationException conflict) {
            OrderRow winner = transactions.findByKey(key);
            if (winner == null) {
                throw conflict;
            }
            return new CheckoutOutcome(resume(access, winner, hash), false);
        }
    }

    public OrderResponse get(Jwt jwt, UUID id) {
        return transactions.response(transactions.get(access(jwt), id));
    }

    public List<OrderResponse> list(Jwt jwt) {
        return transactions.list(access(jwt));
    }

    public OrderResponse cancel(Jwt jwt, UUID id) {
        OrderAccess access = access(jwt);
        OrderRow order = transactions.get(access, id);
        if ("CANCELLED".equals(order.status())) {
            return transactions.response(order);
        }
        if (!"PENDING_PAYMENT".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order can no longer be cancelled.");
        }
        if (order.paymentId() != null) {
            try {
                downstream.cancelPayment(order.paymentId());
            } catch (DownstreamFailure failure) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order can no longer be cancelled.");
            }
        }
        releaseAll(order.lines());
        return transactions.response(transactions.mark(order.id(), "CANCELLED", clock.instant()));
    }

    private OrderResponse resume(OrderAccess access, OrderRow order, String hash) {
        if (!order.customerId().equals(access.subject()) || !order.requestHash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key is already in use.");
        }
        if (!"PENDING_PAYMENT".equals(order.status())) {
            return transactions.response(order);
        }
        return finish(order);
    }

    private OrderResponse finish(OrderRow order) {
        OrderRow current = order;
        if (current.paymentId() == null) {
            OrderRow pending = current;
            PaymentState created = payment(() -> downstream.createPayment(
                    paymentKey(pending.id()), pending.id(), pending.total(), pending.currency()));
            current = transactions.savePayment(pending.id(), created.id(), clock.instant());
        }
        UUID paymentId = current.paymentId();
        String outcome = current.outcome();
        PaymentState result = payment(() -> downstream.simulate(paymentId, outcome));
        if ("SUCCEEDED".equals(result.status())) {
            commitAll(current.lines());
            return transactions.response(transactions.mark(current.id(), "CONFIRMED", clock.instant()));
        }
        if ("FAILED".equals(result.status())) {
            releaseAll(current.lines());
            return transactions.response(transactions.mark(current.id(), "PAYMENT_FAILED", clock.instant()));
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, RETRY);
    }

    private List<Priced> price(List<CheckoutItem> items) {
        List<Priced> priced = new ArrayList<>();
        for (CheckoutItem item : items) {
            CatalogProduct product = fetchProduct(item.productId());
            if (!"ACTIVE".equals(product.status()) || product.price() == null || product.currency() == null
                    || !product.currency().matches("[A-Z]{3}")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Product is not available.");
            }
            BigDecimal unit = money(product.price());
            if (unit.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Product is not available.");
            }
            priced.add(new Priced(item, product, unit));
        }
        String currency = priced.getFirst().product().currency();
        if (priced.stream().anyMatch(line -> !line.product().currency().equals(currency))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Products use more than one currency.");
        }
        return priced;
    }

    private CatalogProduct fetchProduct(UUID id) {
        try {
            return downstream.product(id);
        } catch (DownstreamFailure failure) {
            if (failure.status() == 404) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Product is not available.");
            }
            throw unavailable(failure);
        }
    }

    private List<Line> reserve(String checkoutKey, List<Priced> priced) {
        List<Line> held = new ArrayList<>();
        for (Priced line : priced) {
            try {
                StockHold hold = downstream.reserve(reserveKey(checkoutKey, line.item().productId()), line.item().productId(), line.item().quantity());
                if (!"HELD".equals(hold.status())) {
                    compensate(held);
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "This Idempotency-Key can no longer reserve stock. Use a new key.");
                }
                BigDecimal lineTotal = line.unitPrice().multiply(BigDecimal.valueOf(line.item().quantity())).setScale(2, RoundingMode.HALF_UP);
                held.add(new Line(
                        line.product().id(),
                        line.product().sellerId(),
                        line.product().sku(),
                        line.product().name(),
                        line.unitPrice(),
                        line.item().quantity(),
                        lineTotal,
                        hold.id()));
            } catch (DownstreamFailure failure) {
                if (failure.status() == 409) {
                    compensate(held);
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Not enough stock is available. Use a new Idempotency-Key to try again.");
                }
                throw unavailable(failure);
            }
        }
        return held;
    }

    private void compensate(List<Line> held) {
        for (Line line : held) {
            try {
                downstream.release(line.reservationId());
            } catch (DownstreamFailure failure) {
                throw unavailable(failure);
            }
        }
    }

    private void commitAll(List<Line> lines) {
        for (Line line : lines) {
            try {
                downstream.commit(line.reservationId());
            } catch (DownstreamFailure failure) {
                if (failure.status() >= 500 || failure.status() == 408) {
                    throw unavailable(failure);
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The stock hold could not be committed.");
            }
        }
    }

    private void releaseAll(List<Line> lines) {
        for (Line line : lines) {
            try {
                downstream.release(line.reservationId());
            } catch (DownstreamFailure failure) {
                if (failure.status() >= 500 || failure.status() == 408) {
                    throw unavailable(failure);
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The stock hold could not be released.");
            }
        }
    }

    private PaymentState payment(Supplier<PaymentState> call) {
        try {
            return call.get();
        } catch (DownstreamFailure failure) {
            throw unavailable(failure);
        }
    }

    private static BigDecimal total(List<Line> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            total = total.add(line.lineTotal());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
    }

    private static String paymentKey(UUID orderId) {
        return "p" + digest(orderId.toString()).substring(0, 32);
    }

    private static String reserveKey(String checkoutKey, UUID productId) {
        return "r" + digest(checkoutKey + "|" + productId).substring(0, 32);
    }

    private static OrderAccess access(Jwt jwt) {
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A customer identity is required.");
        }
        return OrderAccess.from(jwt);
    }

    private static String validKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is invalid");
        }
        return key;
    }

    private static List<CheckoutItem> uniqueItems(List<CheckoutItem> items) {
        Set<UUID> seen = new HashSet<>();
        for (CheckoutItem item : items) {
            if (!seen.add(item.productId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "items is invalid");
            }
        }
        return items;
    }

    private static String hash(String customer, String outcome, List<CheckoutItem> items) {
        String body = items.stream()
                .sorted(Comparator.comparing(CheckoutItem::productId))
                .map(item -> item.productId() + ":" + item.quantity())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return digest(customer + "|" + outcome + "|" + body);
    }

    private static String digest(String source) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static ResponseStatusException unavailable(DownstreamFailure failure) {
        if (failure.status() >= 500 || failure.status() == 408 || failure.status() == 429) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, RETRY);
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Checkout was rejected by another service.");
    }

    private record Priced(CheckoutItem item, CatalogProduct product, BigDecimal unitPrice) {
    }
}

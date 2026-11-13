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

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.order.api.CheckoutItem;
import com.bazaar.order.checkout.DownstreamClient.CatalogProduct;
import com.bazaar.order.checkout.OrderTransactions.Line;
import com.bazaar.order.checkout.OrderTransactions.OrderRow;

@Service
public class CheckoutService {

    private final OrderTransactions transactions;
    private final DownstreamClient downstream;
    private final CheckoutMetrics metrics;
    private final Clock clock;

    public CheckoutService(OrderTransactions transactions, DownstreamClient downstream, CheckoutMetrics metrics, Clock clock) {
        this.transactions = transactions;
        this.downstream = downstream;
        this.metrics = metrics;
        this.clock = clock;
    }

    public CheckoutOutcome checkout(Jwt jwt, String idempotencyKey, List<CheckoutItem> items, String outcome, UUID campaignId) {
        metrics.attempt();
        try {
            OrderAccess access = access(jwt);
            String key = validKey(idempotencyKey);
            List<CheckoutItem> lines = uniqueItems(items);
            String hash = hash(access.subject(), outcome, campaignId, lines);
            OrderRow existing = transactions.findByKey(key);
            if (existing != null) {
                CheckoutOutcome replayed = new CheckoutOutcome(replay(access, existing, hash), false);
                metrics.replay();
                return replayed;
            }
            List<Priced> priced = price(lines, campaignId);
            try {
                OrderRow created = transactions.insert(
                        access,
                        key,
                        hash,
                        outcome,
                        priced.getFirst().product().currency(),
                        total(priced),
                        snapshot(priced),
                        clock.instant(),
                        campaignId);
                metrics.accepted();
                return new CheckoutOutcome(transactions.response(created), true);
            } catch (DataIntegrityViolationException conflict) {
                OrderRow winner = transactions.findByKey(key);
                if (winner == null) {
                    throw conflict;
                }
                CheckoutOutcome replayed = new CheckoutOutcome(replay(access, winner, hash), false);
                metrics.replay();
                return replayed;
            }
        } catch (RuntimeException exception) {
            metrics.rejected();
            throw exception;
        }
    }

    public OrderResponse get(Jwt jwt, UUID id) {
        return transactions.response(transactions.get(access(jwt), id));
    }

    public List<OrderResponse> list(Jwt jwt) {
        return transactions.list(access(jwt));
    }

    public SellerSalesPage sales(Jwt jwt, int page, int size, String status, String query) {
        OrderAccess access = access(jwt);
        if (!access.seller()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a seller can read their sales.");
        }
        if (page < 0 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page is invalid");
        }
        String normalizedStatus = blank(status);
        if (normalizedStatus != null && !OrderTransactions.saleStatus(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is invalid");
        }
        String normalizedQuery = blank(query);
        if (normalizedQuery != null && normalizedQuery.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is invalid");
        }
        return transactions.sales(access, page, size, normalizedStatus, normalizedQuery);
    }

    private static String blank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public OrderResponse cancel(Jwt jwt, UUID id) {
        OrderAccess access = access(jwt);
        OrderRow order = transactions.get(access, id);
        return transactions.response(transactions.cancel(order, clock.instant()));
    }

    private OrderResponse replay(OrderAccess access, OrderRow order, String hash) {
        if (!order.customerId().equals(access.subject()) || !order.requestHash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key is already in use.");
        }
        return transactions.response(order);
    }

    private List<Priced> price(List<CheckoutItem> items, UUID campaignId) {
        DownstreamClient.CampaignQuote campaign = campaignId == null ? null : fetchCampaign(campaignId);
        if (campaign != null && (items.size() != 1 || !items.getFirst().productId().equals(campaign.productId())
                || !"ACTIVE".equals(campaign.status()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The campaign does not apply to this checkout.");
        }
        List<Priced> priced = new ArrayList<>();
        for (CheckoutItem item : items) {
            CatalogProduct product = fetchProduct(item.productId());
            if (!"ACTIVE".equals(product.status()) || product.price() == null || product.currency() == null
                    || !product.currency().matches("[A-Z]{3}")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Product is not available.");
            }
            BigDecimal unit = campaign == null ? money(product.price()) : money(campaign.price());
            if (campaign != null && !campaign.currency().equals(product.currency())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The campaign does not apply to this checkout.");
            }
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

    private DownstreamClient.CampaignQuote fetchCampaign(UUID id) {
        try {
            return downstream.campaign(id);
        } catch (DownstreamFailure failure) {
            if (failure.status() == 404) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The campaign does not apply to this checkout.");
            }
            throw unavailable(failure);
        }
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

    private static List<Line> snapshot(List<Priced> priced) {
        List<Line> lines = new ArrayList<>();
        for (Priced line : priced) {
            BigDecimal lineTotal = line.unitPrice().multiply(BigDecimal.valueOf(line.item().quantity())).setScale(2, RoundingMode.HALF_UP);
            lines.add(new Line(
                    line.product().id(),
                    line.product().sellerId(),
                    line.product().sku(),
                    line.product().name(),
                    line.unitPrice(),
                    line.item().quantity(),
                    lineTotal,
                    null,
                    null));
        }
        return lines;
    }

    private static BigDecimal total(List<Priced> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (Priced line : lines) {
            total = total.add(line.unitPrice().multiply(BigDecimal.valueOf(line.item().quantity())));
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

    private static String hash(String customer, String outcome, UUID campaignId, List<CheckoutItem> items) {
        String body = items.stream()
                .sorted(Comparator.comparing(CheckoutItem::productId))
                .map(item -> item.productId() + ":" + item.quantity())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String campaign = campaignId == null ? "" : campaignId.toString();
        return digest(customer + "|" + outcome + "|" + campaign + "|" + body);
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
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Checkout could not be completed. Retry with the same Idempotency-Key.");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Checkout was rejected by another service.");
    }

    private record Priced(CheckoutItem item, CatalogProduct product, BigDecimal unitPrice) {
    }
}

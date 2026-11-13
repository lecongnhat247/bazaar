package com.bazaar.order.api;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.order.checkout.OrderAccess;
import com.bazaar.order.market.CouponView;
import com.bazaar.order.market.Coupons;
import com.bazaar.order.market.FulfillmentView;
import com.bazaar.order.market.Fulfillments;
import com.bazaar.order.market.Ledger;
import com.bazaar.order.market.Settlements;

import jakarta.validation.Valid;

/**
 * Marketplace workflows: seller fulfillment, coupons, commission, ledger and mock settlement.
 * Literal paths win over {@code /{id}}, so these live under the same gateway route as orders.
 *
 * <p>Roles: SELLER acts on its own data only (the seller id always comes from the token),
 * ADMIN writes, OPERATOR reads.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class MarketController {

    private final Fulfillments fulfillments;
    private final Coupons coupons;
    private final Ledger ledger;
    private final Settlements settlements;
    private final Clock clock;

    public MarketController(
            Fulfillments fulfillments, Coupons coupons, Ledger ledger, Settlements settlements, Clock clock) {
        this.fulfillments = fulfillments;
        this.coupons = coupons;
        this.ledger = ledger;
        this.settlements = settlements;
        this.clock = clock;
    }

    // ---- seller fulfillment ----

    @PatchMapping("/{id}/fulfillment")
    public FulfillmentView fulfil(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody FulfillmentRequest request) {
        OrderAccess access = seller(jwt);
        return fulfillments.advance(id, access.subject(), access.subject(), request.status(),
                request.shipmentReference(), request.note(), clock.instant());
    }

    // ---- coupons ----

    @GetMapping("/coupons")
    public List<CouponView> coupons(@AuthenticationPrincipal Jwt jwt) {
        staff(jwt);
        return coupons.list();
    }

    @PostMapping("/coupons")
    public ResponseEntity<CouponView> createCoupon(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewCouponRequest request) {
        OrderAccess access = admin(jwt);
        CouponView created = coupons.create(access.subject(), request.toNewCoupon(), clock.instant());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/coupons/{code}")
    public CouponView couponStatus(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String code,
            @Valid @RequestBody CouponStatusRequest request) {
        admin(jwt);
        return coupons.setStatus(code, request.status(), clock.instant());
    }

    // ---- finance ----

    @GetMapping("/finance/me")
    public Ledger.SellerFinance myFinance(@AuthenticationPrincipal Jwt jwt) {
        OrderAccess access = seller(jwt);
        return ledger.sellerFinance(access.subject(), clock.instant());
    }

    @GetMapping("/finance/ledger")
    public Ledger.LedgerPage ledger(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) String sellerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        staff(jwt);
        return ledger.entries(orderId, sellerId == null || sellerId.isBlank() ? null : sellerId.trim(), page, size);
    }

    @GetMapping("/finance/reconciliation")
    public Ledger.Reconciliation reconciliation(@AuthenticationPrincipal Jwt jwt) {
        staff(jwt);
        return ledger.reconcile();
    }

    @GetMapping("/finance/commission-rates")
    public CommissionRates rates(@AuthenticationPrincipal Jwt jwt) {
        staff(jwt);
        return new CommissionRates(ledger.defaultBps(), ledger.rates());
    }

    @PostMapping("/finance/commission-rates")
    public ResponseEntity<Ledger.RateView> setRate(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RateRequest request) {
        OrderAccess access = admin(jwt);
        Ledger.RateView created = ledger.setRate(request.sellerId().trim(), request.rateBps(), access.subject(),
                clock.instant());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/finance/settlements")
    public List<Settlements.SettlementView> settlements(@AuthenticationPrincipal Jwt jwt) {
        staff(jwt);
        return settlements.list();
    }

    @PostMapping("/finance/settlements")
    public Settlements.Run settle(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) SettlementRequest request) {
        OrderAccess access = admin(jwt);
        String sellerId = request == null || request.sellerId() == null || request.sellerId().isBlank()
                ? null : request.sellerId().trim();
        return settlements.run(idempotencyKey, sellerId, access.subject(), clock.instant());
    }

    @PostMapping("/finance/refunds")
    public RefundResult refund(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RefundRequest request) {
        OrderAccess access = admin(jwt);
        int reversed = ledger.refund(request.orderId(), request.sellerId().trim(), request.reason().trim(),
                access.subject(), clock.instant());
        return new RefundResult(request.orderId(), request.sellerId().trim(), reversed, reversed == 0);
    }

    public record CommissionRates(int defaultRateBps, List<Ledger.RateView> rates) {
    }

    public record RefundResult(UUID orderId, String sellerId, int reversedEntries, boolean alreadyRefunded) {
    }

    private static OrderAccess seller(Jwt jwt) {
        OrderAccess access = OrderAccess.from(jwt);
        if (!access.seller()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a seller can do this.");
        }
        return access;
    }

    private static OrderAccess staff(Jwt jwt) {
        OrderAccess access = OrderAccess.from(jwt);
        if (!access.staff()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only staff can do this.");
        }
        return access;
    }

    private static OrderAccess admin(Jwt jwt) {
        OrderAccess access = OrderAccess.from(jwt);
        if (!access.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can do this.");
        }
        return access;
    }
}

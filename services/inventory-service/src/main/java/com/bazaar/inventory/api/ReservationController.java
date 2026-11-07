package com.bazaar.inventory.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaar.inventory.stock.InventoryService;
import com.bazaar.inventory.stock.ReserveOutcome;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/inventory/reservations")
public class ReservationController {

    private final InventoryService inventory;

    public ReservationController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @PostMapping
    public ResponseEntity<ReservationBody> reserve(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReserveRequest request) {
        ReserveOutcome outcome = inventory.reserve(jwt, idempotencyKey, request.productId(), request.quantity());
        ReservationBody body = ReservationBody.from(outcome.reservation());
        if (outcome.created()) {
            return ResponseEntity.status(201).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/release")
    public ReservationBody release(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ReservationBody.from(inventory.release(jwt, id));
    }

    @PostMapping("/{id}/commit")
    public ReservationBody commit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ReservationBody.from(inventory.commit(jwt, id));
    }
}

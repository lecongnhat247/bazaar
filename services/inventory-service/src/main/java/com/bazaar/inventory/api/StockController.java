package com.bazaar.inventory.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaar.inventory.stock.InventoryService;
import com.bazaar.inventory.stock.StockResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/inventory/products")
public class StockController {

    private final InventoryService inventory;

    public StockController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/{productId}")
    public StockResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        return inventory.get(jwt, productId);
    }

    @PutMapping("/{productId}")
    public StockResponse set(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId, @Valid @RequestBody StockRequest request) {
        return inventory.setOnHand(jwt, productId, request.onHand());
    }
}

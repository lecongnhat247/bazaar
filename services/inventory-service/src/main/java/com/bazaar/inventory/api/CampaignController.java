package com.bazaar.inventory.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaar.inventory.stock.FlashSales;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/inventory/campaigns")
public class CampaignController {

    private final FlashSales campaigns;

    public CampaignController(FlashSales campaigns) {
        this.campaigns = campaigns;
    }

    @PostMapping
    public ResponseEntity<CampaignResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CampaignRequest request) {
        CampaignResponse created = campaigns.create(jwt, request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/campaigns/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public CampaignResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return campaigns.get(jwt, id);
    }

    @PostMapping("/{id}/schedule")
    public CampaignResponse schedule(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return campaigns.schedule(jwt, id);
    }

    @PostMapping("/{id}/cancel")
    public CampaignResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return campaigns.cancel(jwt, id);
    }

    @PostMapping("/{id}/reconcile")
    public CampaignReconcile reconcile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return campaigns.reconcile(jwt, id);
    }
}

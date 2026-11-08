package com.bazaar.inventory.stock;

import java.time.Clock;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.bazaar.inventory.api.CampaignReconcile;
import com.bazaar.inventory.api.CampaignRequest;
import com.bazaar.inventory.api.CampaignResponse;

@Service
public class FlashSales {

    private final FlashCampaigns campaigns;
    private final InventoryTransactions transactions;
    private final Clock clock;

    public FlashSales(FlashCampaigns campaigns, InventoryTransactions transactions, Clock clock) {
        this.campaigns = campaigns;
        this.transactions = transactions;
        this.clock = clock;
    }

    public CampaignResponse create(Jwt jwt, CampaignRequest request) {
        return campaigns.create(
                InventoryAccess.from(jwt),
                request.productId(),
                request.price(),
                request.currency(),
                request.quota(),
                request.perCustomerLimit(),
                request.startsAt(),
                request.endsAt(),
                clock.instant());
    }

    public CampaignResponse get(Jwt jwt, UUID id) {
        return campaigns.visible(InventoryAccess.from(jwt), id, clock.instant());
    }

    public CampaignResponse schedule(Jwt jwt, UUID id) {
        return campaigns.schedule(InventoryAccess.from(jwt), id, clock.instant());
    }

    public CampaignResponse cancel(Jwt jwt, UUID id) {
        return transactions.cancelCampaign(InventoryAccess.from(jwt), id, clock.instant());
    }

    public CampaignReconcile reconcile(Jwt jwt, UUID id) {
        return campaigns.reconcile(InventoryAccess.from(jwt), id);
    }
}

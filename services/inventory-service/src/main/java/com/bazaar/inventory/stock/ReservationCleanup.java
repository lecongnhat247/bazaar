package com.bazaar.inventory.stock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationCleanup {

    private final InventoryService inventory;

    public ReservationCleanup(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanExpiredReservations() {
        inventory.expireDue();
    }
}

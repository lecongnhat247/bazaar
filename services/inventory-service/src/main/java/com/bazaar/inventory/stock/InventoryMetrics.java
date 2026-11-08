package com.bazaar.inventory.stock;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class InventoryMetrics {

    private final Counter reserved;
    private final Counter replayed;
    private final Counter rejected;
    private final Counter expired;

    public InventoryMetrics(MeterRegistry meters) {
        this.reserved = meters.counter("bazaar.inventory.reservations", "result", "reserved");
        this.replayed = meters.counter("bazaar.inventory.reservations", "result", "replay");
        this.rejected = meters.counter("bazaar.inventory.reservations", "result", "rejected");
        this.expired = meters.counter("bazaar.inventory.expired");
    }

    public void reserved() {
        reserved.increment();
    }

    public void replay() {
        replayed.increment();
    }

    public void rejected() {
        rejected.increment();
    }

    public void expired(int count) {
        if (count > 0) {
            expired.increment(count);
        }
    }
}

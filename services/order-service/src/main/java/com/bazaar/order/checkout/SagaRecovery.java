package com.bazaar.order.checkout;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SagaRecovery {

    private final SagaCoordinator saga;

    public SagaRecovery(SagaCoordinator saga) {
        this.saga = saga;
    }

    @Scheduled(fixedDelayString = "${bazaar.saga.recover-delay:15000}", initialDelayString = "${bazaar.saga.recover-delay:15000}")
    public void recover() {
        saga.recover();
    }
}

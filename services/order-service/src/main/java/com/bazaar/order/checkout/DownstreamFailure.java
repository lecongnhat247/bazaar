package com.bazaar.order.checkout;

class DownstreamFailure extends RuntimeException {

    private final int status;

    DownstreamFailure(int status) {
        this.status = status;
    }

    int status() {
        return status;
    }
}

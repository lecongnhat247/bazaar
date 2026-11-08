package com.bazaar.inventory.stock;

public class StockShortException extends RuntimeException {

    public StockShortException(String message) {
        super(message);
    }
}

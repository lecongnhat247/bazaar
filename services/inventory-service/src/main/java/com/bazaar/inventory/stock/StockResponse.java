package com.bazaar.inventory.stock;

import java.util.UUID;

public record StockResponse(UUID productId, int available, int reserved, String sellerId) {
}

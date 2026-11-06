package com.bazaar.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;

import com.bazaar.catalog.product.ProductStatus;

public record ProductSearch(
        int page,
        int size,
        String sort,
        String direction,
        String keyword,
        UUID categoryId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        ProductStatus status,
        boolean mine) {
}

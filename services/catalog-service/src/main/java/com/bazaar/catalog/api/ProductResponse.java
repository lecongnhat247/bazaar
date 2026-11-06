package com.bazaar.catalog.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bazaar.catalog.product.Product;
import com.bazaar.catalog.product.ProductStatus;

public record ProductResponse(
        UUID id,
        String sellerId,
        UUID categoryId,
        String name,
        String description,
        String sku,
        BigDecimal price,
        String currency,
        ProductStatus status,
        List<ImageResponse> images,
        Map<String, String> attributes,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSellerId(),
                product.getCategory().getId(),
                product.getName(),
                product.getDescription(),
                product.getSku(),
                product.getPrice(),
                product.getCurrency(),
                product.getStatus(),
                product.getImages().stream()
                        .map(image -> new ImageResponse(image.getId(), image.getUrl(), image.getAltText(), image.getPosition()))
                        .toList(),
                Map.copyOf(product.getAttributes()),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}

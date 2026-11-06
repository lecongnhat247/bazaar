package com.bazaar.catalog.api;

import com.bazaar.catalog.product.ProductStatus;

import jakarta.validation.constraints.NotNull;

public record StatusRequest(@NotNull ProductStatus status) {
}

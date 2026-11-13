package com.bazaar.catalog.api;

import com.bazaar.catalog.product.ProductStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StatusRequest(@NotNull ProductStatus status, @Size(max = 500) String reason) {
}

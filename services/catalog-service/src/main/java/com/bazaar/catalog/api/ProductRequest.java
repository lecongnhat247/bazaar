package com.bazaar.catalog.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProductRequest(
        @NotNull UUID categoryId,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 4000) String description,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String sku,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal price,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @Size(max = 8) List<@Valid ImageRequest> images,
        Map<String, String> attributes) {
}

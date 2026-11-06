package com.bazaar.catalog.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ImageRequest(
        @NotBlank @Size(max = 500) String url,
        @Size(max = 160) String altText) {
}

package com.bazaar.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StoreRequest(
        @NotBlank @Size(max = 120) String storeName,
        @Size(max = 4000) String description,
        @Size(max = 500) String logoUrl,
        @Size(max = 320) String contactEmail,
        @Size(max = 40) String phone) {
}

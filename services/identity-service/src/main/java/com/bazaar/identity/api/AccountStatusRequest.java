package com.bazaar.identity.api;

import com.bazaar.identity.profile.AccountStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AccountStatusRequest(
        @NotNull AccountStatus status,
        @NotBlank @Size(max = 500) String reason) {
}

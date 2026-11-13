package com.bazaar.identity.api;

import java.time.Instant;
import java.util.UUID;

import com.bazaar.identity.seller.SellerProfile;
import com.bazaar.identity.seller.StoreProfile;

public record SellerResponse(
        UUID id,
        String keycloakSubject,
        String status,
        String storeName,
        String description,
        String logoUrl,
        String contactEmail,
        String phone,
        Instant createdAt,
        Instant updatedAt) {

    public static SellerResponse from(SellerProfile seller, StoreProfile store) {
        return new SellerResponse(
                seller.getId(),
                seller.getKeycloakSubject(),
                seller.getStatus().name(),
                store.getStoreName(),
                store.getDescription(),
                store.getLogoUrl(),
                store.getContactEmail(),
                store.getPhone(),
                seller.getCreatedAt(),
                store.getUpdatedAt());
    }
}

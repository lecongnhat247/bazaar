package com.bazaar.identity.api;

import java.util.List;
import java.util.UUID;

import com.bazaar.identity.profile.UserProfile;

public record UserProfileResponse(
        UUID id,
        String keycloakSubject,
        String email,
        String displayName,
        String status,
        List<String> roles) {

    public static UserProfileResponse from(UserProfile profile) {
        return new UserProfileResponse(
                profile.getId(),
                profile.getKeycloakSubject(),
                profile.getEmail(),
                profile.getDisplayName(),
                profile.getStatus().name(),
                profile.getRoles().stream().sorted().toList());
    }
}

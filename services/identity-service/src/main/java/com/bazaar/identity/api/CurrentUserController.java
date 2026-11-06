package com.bazaar.identity.api;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaar.identity.profile.UserProfileService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/identity")
public class CurrentUserController {

    private final UserProfileService profiles;

    public CurrentUserController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/me")
    public UserProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserProfileResponse.from(profiles.currentUser(jwt));
    }

    @PatchMapping("/me")
    public UserProfileResponse updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return UserProfileResponse.from(profiles.updateDisplayName(jwt, request.displayName()));
    }

    @GetMapping("/users/{id}")
    public UserProfileResponse user(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return UserProfileResponse.from(profiles.getById(jwt, id));
    }
}

package com.bazaar.identity.api;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bazaar.identity.profile.AuditQuery;
import com.bazaar.identity.profile.UserProfile;
import com.bazaar.identity.profile.UserProfileService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/identity")
public class CurrentUserController {

    private final UserProfileService profiles;
    private final AuditQuery audits;

    public CurrentUserController(UserProfileService profiles, AuditQuery audits) {
        this.profiles = profiles;
        this.audits = audits;
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

    @GetMapping("/users")
    public StaffPage<UserProfileResponse> users(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        StaffPage<UserProfile> result = profiles.list(jwt, q, page, size);
        return new StaffPage<>(
                result.items().stream().map(UserProfileResponse::from).toList(),
                result.page(),
                result.size(),
                result.totalItems(),
                result.totalPages());
    }

    @PatchMapping("/users/{id}/status")
    public UserProfileResponse status(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody AccountStatusRequest request) {
        return UserProfileResponse.from(profiles.changeStatus(jwt, id, request.status(), request.reason()));
    }

    @GetMapping("/audit")
    public StaffPage<AuditEntry> audit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return audits.list(jwt, page, size);
    }
}

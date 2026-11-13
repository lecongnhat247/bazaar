package com.bazaar.identity.profile;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.identity.api.StaffPage;

@Service
public class UserProfileService {

    static final Set<String> APPLICATION_ROLES = Set.of("CUSTOMER", "SELLER", "ADMIN", "OPERATOR");

    private final UserProfileRepository profiles;
    private final UserAuditRepository audits;
    private final TransactionTemplate newTransaction;

    public UserProfileService(
            UserProfileRepository profiles,
            UserAuditRepository audits,
            PlatformTransactionManager transactions) {
        this.profiles = profiles;
        this.audits = audits;
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public UserProfile currentUser(Jwt jwt) {
        String subject = jwt.getSubject();
        UserProfile profile = profiles.findByKeycloakSubject(subject).orElseGet(() -> loadCreated(jwt));
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            profile.setEmail(email);
        }
        replaceRoles(profile, rolesFrom(jwt));
        return profile;
    }

    @Transactional
    public UserProfile updateDisplayName(Jwt jwt, String displayName) {
        UserProfile profile = currentUser(jwt);
        profile.setDisplayName(displayName.trim());
        profile.setUpdatedAt(Instant.now());
        return profile;
    }

    @Transactional
    public UserProfile getById(Jwt jwt, UUID id) {
        UserProfile current = currentUser(jwt);
        UserProfile target = profiles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User profile was not found."));
        if (!current.getId().equals(target.getId()) && !staff(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot read this profile.");
        }
        return target;
    }

    @Transactional(readOnly = true)
    public StaffPage<UserProfile> list(Jwt jwt, String query, int page, int size) {
        if (!staff(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only staff can list users.");
        }
        if (page < 0 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page is invalid");
        }
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UserProfile> result = query == null || query.isBlank()
                ? profiles.findAll(request)
                : profiles.search(like(query), request);
        return new StaffPage<>(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public UserProfile changeStatus(Jwt jwt, UUID id, AccountStatus status, String reason) {
        if (!admin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can change account status.");
        }
        UserProfile actor = currentUser(jwt);
        UserProfile target = profiles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User profile was not found."));
        if (actor.getId().equals(target.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change your own account status.");
        }
        Instant now = Instant.now();
        target.setStatus(status);
        target.setUpdatedAt(now);
        audits.save(new UserAudit(
                UUID.randomUUID(),
                actor.getKeycloakSubject(),
                "ACCOUNT_STATUS",
                target.getKeycloakSubject(),
                reason.trim(),
                status.name(),
                now));
        return target;
    }

    private static boolean admin(Jwt jwt) {
        return rolesFrom(jwt).contains("ADMIN");
    }

    private static boolean staff(Jwt jwt) {
        Set<String> roles = rolesFrom(jwt);
        return roles.contains("ADMIN") || roles.contains("OPERATOR");
    }

    private static String like(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private UserProfile loadCreated(Jwt jwt) {
        create(jwt);
        return profiles.findByKeycloakSubject(jwt.getSubject())
                .orElseThrow(() -> new IllegalStateException("Profile was not stored."));
    }

    private UserProfile create(Jwt jwt) {
        Instant now = Instant.now();
        UserProfile profile = new UserProfile(
                UUID.randomUUID(),
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                displayNameFrom(jwt),
                now);
        replaceRoles(profile, rolesFrom(jwt));
        try {
            return newTransaction.execute(status -> profiles.saveAndFlush(profile));
        } catch (DataIntegrityViolationException conflict) {
            return profiles.findByKeycloakSubject(jwt.getSubject()).orElseThrow(() -> conflict);
        }
    }

    private static void replaceRoles(UserProfile profile, Set<String> roles) {
        profile.getRoles().clear();
        profile.getRoles().addAll(roles);
    }

    private static String displayNameFrom(Jwt jwt) {
        String preferred = jwt.getClaimAsString("preferred_username");
        String email = jwt.getClaimAsString("email");
        String source = preferred != null && !preferred.isBlank()
                ? preferred
                : email != null && !email.isBlank() ? email : jwt.getSubject();
        String trimmed = source.trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> rolesFrom(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof Map<?, ?> map)) {
            return Set.of();
        }
        Object roles = map.get("roles");
        if (!(roles instanceof Collection<?> values)) {
            return Set.of();
        }
        Set<String> accepted = new HashSet<>();
        for (Object value : values) {
            if (value instanceof String role && APPLICATION_ROLES.contains(role)) {
                accepted.add(role);
            }
        }
        return accepted;
    }
}

package com.bazaar.catalog.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;

public record CatalogAccess(String subject, Set<String> roles) {

    public boolean admin() {
        return roles.contains("ADMIN");
    }

    public boolean seller() {
        return admin() || roles.contains("SELLER");
    }

    public static CatalogAccess from(Jwt jwt) {
        return new CatalogAccess(jwt.getSubject(), rolesFrom(jwt));
    }

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
            if (value instanceof String role) {
                accepted.add(role);
            }
        }
        return accepted;
    }
}

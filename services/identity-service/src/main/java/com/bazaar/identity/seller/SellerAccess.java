package com.bazaar.identity.seller;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;

record SellerAccess(String subject, Set<String> roles) {

    boolean seller() {
        return roles.contains("SELLER");
    }

    boolean admin() {
        return roles.contains("ADMIN");
    }

    static SellerAccess from(Jwt jwt) {
        return new SellerAccess(jwt.getSubject(), rolesFrom(jwt));
    }

    private static Set<String> rolesFrom(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof Map<?, ?> map) || !(map.get("roles") instanceof Collection<?> values)) {
            return Set.of();
        }
        Set<String> accepted = new HashSet<>();
        for (Object value : values) {
            if (value instanceof String role) {
                accepted.add(role);
            }
        }
        return Set.copyOf(accepted);
    }
}

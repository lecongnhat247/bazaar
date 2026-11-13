package com.bazaar.inventory.seller;

import org.springframework.security.oauth2.jwt.Jwt;

public interface SellerStatusCheck {

    void requireActive(Jwt jwt);
}

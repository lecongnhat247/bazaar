package com.bazaar.identity.seller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.identity.api.SellerResponse;
import com.bazaar.identity.api.StoreRequest;

@Service
public class SellerService {

    private final SellerProfileRepository sellers;
    private final StoreProfileRepository stores;
    private final SellerAuditRepository audits;

    public SellerService(
            SellerProfileRepository sellers,
            StoreProfileRepository stores,
            SellerAuditRepository audits) {
        this.sellers = sellers;
        this.stores = stores;
        this.audits = audits;
    }

    @Transactional
    public SellerResponse open(Jwt jwt, StoreRequest request) {
        SellerAccess access = SellerAccess.from(jwt);
        if (!access.seller()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a seller can open a store.");
        }
        if (sellers.findByKeycloakSubject(access.subject()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A store already exists.");
        }
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        SellerProfile seller = new SellerProfile(id, access.subject(), SellerStatus.PENDING_APPROVAL, now);
        StoreProfile store = new StoreProfile(
                id,
                request.storeName().trim(),
                blankToNull(request.description()),
                logo(request.logoUrl()),
                email(request.contactEmail()),
                phone(request.phone()),
                now);
        try {
            sellers.saveAndFlush(seller);
            stores.saveAndFlush(store);
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A store already exists.");
        }
        return SellerResponse.from(seller, store);
    }

    @Transactional(readOnly = true)
    public SellerResponse current(Jwt jwt) {
        SellerProfile seller = sellers.findByKeycloakSubject(jwt.getSubject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller profile was not found."));
        return SellerResponse.from(seller, requiredStore(seller.getId()));
    }

    @Transactional
    public SellerResponse updateStore(Jwt jwt, StoreRequest request) {
        SellerAccess access = SellerAccess.from(jwt);
        if (!access.seller() && !access.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a seller can edit this store.");
        }
        SellerProfile seller = sellers.findByKeycloakSubject(access.subject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller profile was not found."));
        if (seller.getStatus() == SellerStatus.SUSPENDED || seller.getStatus() == SellerStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This store cannot be edited.");
        }
        StoreProfile store = requiredStore(seller.getId());
        Instant now = Instant.now();
        store.setStoreName(request.storeName().trim());
        store.setDescription(blankToNull(request.description()));
        store.setLogoUrl(logo(request.logoUrl()));
        store.setContactEmail(email(request.contactEmail()));
        store.setPhone(phone(request.phone()));
        store.setUpdatedAt(now);
        seller.setUpdatedAt(now);
        return SellerResponse.from(seller, store);
    }

    @Transactional
    public SellerResponse approve(Jwt jwt, String subject, String reason) {
        return decide(jwt, subject, reason, "APPROVE", SellerStatus.PENDING_APPROVAL, SellerStatus.ACTIVE);
    }

    @Transactional
    public SellerResponse reject(Jwt jwt, String subject, String reason) {
        return decide(jwt, subject, reason, "REJECT", SellerStatus.PENDING_APPROVAL, SellerStatus.REJECTED);
    }

    @Transactional
    public SellerResponse suspend(Jwt jwt, String subject, String reason) {
        return decide(jwt, subject, reason, "SUSPEND", SellerStatus.ACTIVE, SellerStatus.SUSPENDED);
    }

    @Transactional
    public SellerResponse reactivate(Jwt jwt, String subject, String reason) {
        return decide(jwt, subject, reason, "REACTIVATE", SellerStatus.SUSPENDED, SellerStatus.ACTIVE);
    }

    private SellerResponse decide(
            Jwt jwt,
            String subject,
            String reason,
            String action,
            SellerStatus expected,
            SellerStatus next) {
        SellerAccess access = SellerAccess.from(jwt);
        if (!access.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can change seller status.");
        }
        if (access.subject().equals(subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change your own seller status.");
        }
        SellerProfile seller = sellers.findByKeycloakSubject(subject)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller profile was not found."));
        if (seller.getStatus() != expected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That status change is not allowed.");
        }
        Instant now = Instant.now();
        seller.setStatus(next);
        seller.setUpdatedAt(now);
        seller.setDecidedAt(now);
        audits.save(new SellerAudit(UUID.randomUUID(), access.subject(), action, subject, reason.trim(), next.name(), now));
        return SellerResponse.from(seller, requiredStore(seller.getId()));
    }

    private StoreProfile requiredStore(UUID sellerId) {
        return stores.findById(sellerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller profile was not found."));
    }

    private static String logo(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "logoUrl is invalid");
        }
        return trimmed;
    }

    private static String email(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (!trimmed.matches("[^@\\s]+@[^@\\s]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contactEmail is invalid");
        }
        return trimmed;
    }

    private static String phone(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (!trimmed.matches("[0-9+() ./-]{1,40}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phone is invalid");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

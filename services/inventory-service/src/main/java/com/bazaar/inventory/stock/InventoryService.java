package com.bazaar.inventory.stock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventoryService {

    private final InventoryTransactions transactions;
    private final Clock clock;
    private final Duration ttl;

    public InventoryService(
            InventoryTransactions transactions,
            Clock clock,
            @Value("${bazaar.inventory.reservation-ttl:PT15M}") Duration ttl) {
        this.transactions = transactions;
        this.clock = clock;
        this.ttl = ttl;
    }

    public StockResponse setOnHand(Jwt jwt, UUID productId, int onHand) {
        InventoryAccess access = InventoryAccess.from(jwt);
        if (!access.seller()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a seller can change stock.");
        }
        if (onHand < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "onHand is invalid");
        }
        try {
            return transactions.insertStock(access, productId, onHand, clock.instant());
        } catch (DataIntegrityViolationException alreadyExists) {
            return transactions.updateStock(access, productId, onHand, clock.instant());
        }
    }

    public StockResponse get(Jwt jwt, UUID productId) {
        transactions.expireDue(clock.instant());
        return transactions.get(productId);
    }

    public ReserveOutcome reserve(Jwt jwt, String idempotencyKey, UUID productId, int quantity) {
        InventoryAccess access = InventoryAccess.from(jwt);
        String key = validKey(idempotencyKey);
        if (quantity < 1 || quantity > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity is invalid");
        }
        String hash = hash(productId, quantity, access.subject());
        transactions.expireDue(clock.instant());
        Instant now = clock.instant();
        try {
            return transactions.reserve(access, key, hash, productId, quantity, now, now.plus(ttl));
        } catch (DataIntegrityViolationException conflict) {
            return new ReserveOutcome(transactions.replay(access, key, hash), false);
        }
    }

    public ReservationResponse release(Jwt jwt, UUID id) {
        transactions.expireDue(clock.instant());
        return transactions.release(InventoryAccess.from(jwt), id, clock.instant());
    }

    public ReservationResponse commit(Jwt jwt, UUID id) {
        transactions.expireDue(clock.instant());
        return transactions.commit(InventoryAccess.from(jwt), id, clock.instant());
    }

    public int expireDue() {
        return transactions.expireDue(clock.instant());
    }

    private static String validKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is invalid");
        }
        return key;
    }

    private static String hash(UUID productId, int quantity, String requester) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((productId + "|" + quantity + "|" + requester).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}

package com.bazaar.catalog.product;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_moderation")
public class ProductModeration {

    @Id
    private UUID id;

    @Column(name = "actor_subject", nullable = false, length = 64)
    private String actorSubject;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "result_status", nullable = false, length = 32)
    private String resultStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProductModeration() {
    }

    public ProductModeration(
            UUID id,
            String actorSubject,
            UUID productId,
            String action,
            String reason,
            String resultStatus,
            Instant createdAt) {
        this.id = id;
        this.actorSubject = actorSubject;
        this.productId = productId;
        this.action = action;
        this.reason = reason;
        this.resultStatus = resultStatus;
        this.createdAt = createdAt;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public String getResultStatus() {
        return resultStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

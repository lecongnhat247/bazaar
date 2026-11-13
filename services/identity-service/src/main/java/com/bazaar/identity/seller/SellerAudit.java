package com.bazaar.identity.seller;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "seller_audit")
public class SellerAudit {

    @Id
    private UUID id;

    @Column(name = "actor_subject", nullable = false, length = 64)
    private String actorSubject;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(name = "target_subject", nullable = false, length = 64)
    private String targetSubject;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "result_status", nullable = false, length = 32)
    private String resultStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SellerAudit() {
    }

    public UUID getId() {
        return id;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public String getAction() {
        return action;
    }

    public String getTargetSubject() {
        return targetSubject;
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

    public SellerAudit(
            UUID id,
            String actorSubject,
            String action,
            String targetSubject,
            String reason,
            String resultStatus,
            Instant createdAt) {
        this.id = id;
        this.actorSubject = actorSubject;
        this.action = action;
        this.targetSubject = targetSubject;
        this.reason = reason;
        this.resultStatus = resultStatus;
        this.createdAt = createdAt;
    }
}

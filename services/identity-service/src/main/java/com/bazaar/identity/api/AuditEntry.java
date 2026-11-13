package com.bazaar.identity.api;

import java.time.Instant;

public record AuditEntry(
        String area,
        String actorSubject,
        String action,
        String targetSubject,
        String reason,
        String resultStatus,
        Instant createdAt) {
}

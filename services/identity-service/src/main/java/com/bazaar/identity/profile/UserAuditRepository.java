package com.bazaar.identity.profile;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuditRepository extends JpaRepository<UserAudit, UUID> {
}

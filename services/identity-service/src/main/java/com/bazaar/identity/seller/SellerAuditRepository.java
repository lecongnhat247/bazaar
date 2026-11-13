package com.bazaar.identity.seller;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerAuditRepository extends JpaRepository<SellerAudit, UUID> {
}

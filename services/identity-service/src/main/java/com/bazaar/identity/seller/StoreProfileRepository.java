package com.bazaar.identity.seller;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreProfileRepository extends JpaRepository<StoreProfile, UUID> {
}

package com.bazaar.identity.seller;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerProfileRepository extends JpaRepository<SellerProfile, UUID> {

    Optional<SellerProfile> findByKeycloakSubject(String keycloakSubject);

    Page<SellerProfile> findByStatus(SellerStatus status, Pageable pageable);
}

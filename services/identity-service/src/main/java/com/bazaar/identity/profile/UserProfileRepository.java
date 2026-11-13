package com.bazaar.identity.profile;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    Optional<UserProfile> findByKeycloakSubject(String keycloakSubject);

    @Query("""
            SELECT profile FROM UserProfile profile
            WHERE LOWER(profile.displayName) LIKE :pattern ESCAPE '\\'
               OR LOWER(COALESCE(profile.email, '')) LIKE :pattern ESCAPE '\\'
               OR LOWER(profile.keycloakSubject) LIKE :pattern ESCAPE '\\'
            """)
    Page<UserProfile> search(@Param("pattern") String pattern, Pageable pageable);
}

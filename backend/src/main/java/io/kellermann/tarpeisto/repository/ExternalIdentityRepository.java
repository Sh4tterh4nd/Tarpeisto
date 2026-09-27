package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.ExternalIdentity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link ExternalIdentity}, uniquely keyed by the immutable {@code
 * (issuer, subject)} pair (ADR-0003).
 */
public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, UUID> {

    /** Returns the row for this pair regardless of {@link ExternalIdentity#isActive()}. */
    Optional<ExternalIdentity> findByIssuerAndSubject(String issuer, String subject);

    List<ExternalIdentity> findAllByUserIdOrderByCreatedAtAsc(UUID userId);
}

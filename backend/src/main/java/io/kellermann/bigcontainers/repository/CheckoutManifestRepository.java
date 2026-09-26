package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.CheckoutManifest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutManifestRepository extends JpaRepository<CheckoutManifest, UUID> {
    Optional<CheckoutManifest> findByOrganizationIdAndBookingId(UUID organizationId, UUID bookingId);

    Optional<CheckoutManifest> findByOrganizationIdAndMutationId(UUID organizationId, UUID mutationId);
}

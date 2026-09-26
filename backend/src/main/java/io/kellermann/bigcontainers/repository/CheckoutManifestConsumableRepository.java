package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.CheckoutManifestConsumable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutManifestConsumableRepository extends JpaRepository<CheckoutManifestConsumable, UUID> {
    List<CheckoutManifestConsumable> findAllByOrganizationIdAndManifestIdOrderById(UUID org, UUID manifestId);

    Optional<CheckoutManifestConsumable> findByIdAndOrganizationIdAndManifestId(UUID id, UUID org, UUID manifestId);
}

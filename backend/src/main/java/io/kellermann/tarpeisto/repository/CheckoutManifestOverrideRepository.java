package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.CheckoutManifestOverride;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutManifestOverrideRepository extends JpaRepository<CheckoutManifestOverride, UUID> {
    List<CheckoutManifestOverride> findAllByOrganizationIdAndManifestIdOrderById(UUID org, UUID manifestId);
}

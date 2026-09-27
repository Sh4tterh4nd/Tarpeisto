package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.CheckoutManifestAsset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutManifestAssetRepository extends JpaRepository<CheckoutManifestAsset, UUID> {
    List<CheckoutManifestAsset> findAllByOrganizationIdAndManifestIdOrderById(UUID org, UUID manifestId);

    Optional<CheckoutManifestAsset> findByOrganizationIdAndManifestIdAndAssetId(
            UUID org, UUID manifestId, UUID assetId);

    boolean existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(UUID org, UUID assetId);

    List<CheckoutManifestAsset> findAllByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(UUID org, UUID assetId);
}

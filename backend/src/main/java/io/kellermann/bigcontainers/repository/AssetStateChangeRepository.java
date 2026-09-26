package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AssetStateChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetStateChangeRepository extends JpaRepository<AssetStateChange, UUID> {

    List<AssetStateChange> findAllByOrganizationIdAndAssetIdOrderByChangedAtDesc(UUID organizationId, UUID assetId);
}

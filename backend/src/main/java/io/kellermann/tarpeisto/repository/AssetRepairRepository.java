package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.AssetRepair;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepairRepository extends JpaRepository<AssetRepair, UUID> {
    List<AssetRepair> findAllByOrganizationIdAndAssetIdOrderByOpenedAtDesc(UUID organizationId, UUID assetId);

    Optional<AssetRepair> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndAssetIdAndClosedAtIsNull(UUID organizationId, UUID assetId);
}

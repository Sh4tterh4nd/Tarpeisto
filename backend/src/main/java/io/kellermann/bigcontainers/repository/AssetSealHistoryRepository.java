package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AssetSealHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetSealHistoryRepository extends JpaRepository<AssetSealHistory, UUID> {
    List<AssetSealHistory> findAllByOrganizationIdAndAssetIdOrderByOccurredAtDesc(UUID organizationId, UUID assetId);
}

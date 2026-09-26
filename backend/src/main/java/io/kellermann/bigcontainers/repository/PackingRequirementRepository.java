package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.PackingRequirement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackingRequirementRepository extends JpaRepository<PackingRequirement, UUID> {
    Optional<PackingRequirement> findByIdAndOrganizationId(UUID id, UUID org);

    List<PackingRequirement> findAllByOrganizationIdAndContainerAssetIdOrderByDisplayOrderAsc(
            UUID org, UUID containerId);

    boolean existsByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(UUID org, UUID assetId);

    Optional<PackingRequirement> findByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(UUID org, UUID assetId);

    boolean existsByOrganizationIdAndContainerAssetIdAndArchivedAtIsNull(UUID org, UUID containerId);

    boolean existsByOrganizationIdAndAssetModelId(UUID organizationId, UUID assetModelId);

    List<PackingRequirement> findAllByOrganizationIdAndArchivedAtIsNull(UUID organizationId);
}

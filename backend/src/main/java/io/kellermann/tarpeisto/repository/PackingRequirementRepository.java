package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.PackingRequirement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PackingRequirementRepository extends JpaRepository<PackingRequirement, UUID> {
    Optional<PackingRequirement> findByIdAndOrganizationId(UUID id, UUID org);

    List<PackingRequirement> findAllByOrganizationIdAndContainerAssetIdOrderByDisplayOrderAsc(
            UUID org, UUID containerId);

    @Query(
            "SELECT DISTINCT r.specificAssetId FROM PackingRequirement r WHERE r.organizationId=:org AND r.archivedAt IS NULL AND r.specificAssetId IN :assetIds")
    List<UUID> findPinnedAssetIds(@Param("org") UUID organizationId, @Param("assetIds") List<UUID> assetIds);

    boolean existsByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(UUID org, UUID assetId);

    Optional<PackingRequirement> findByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(UUID org, UUID assetId);

    boolean existsByOrganizationIdAndContainerAssetIdAndArchivedAtIsNull(UUID org, UUID containerId);

    boolean existsByOrganizationIdAndAssetModelId(UUID organizationId, UUID assetModelId);

    List<PackingRequirement> findAllByOrganizationIdAndArchivedAtIsNull(UUID organizationId);
}

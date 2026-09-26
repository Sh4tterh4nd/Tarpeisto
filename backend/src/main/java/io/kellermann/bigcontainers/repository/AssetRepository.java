package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.LifecycleState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    Optional<Asset> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Asset> findByOrganizationIdAndPublicCode(UUID organizationId, String publicCode);

    /** Used as {@code AssetCodeUniquenessChecker} (a method reference) by {@code AssetService}. */
    boolean existsByOrganizationIdAndPublicCode(UUID organizationId, String publicCode);

    boolean existsByOrganizationIdAndAssetModelId(UUID organizationId, UUID assetModelId);

    List<Asset> findAllByOrganizationIdAndAssetModelIdOrderByUnitNumberAsc(UUID organizationId, UUID assetModelId);

    /** "Normal" results only (specification section 8.4): active lifecycle, not archived. */
    List<Asset> findAllByOrganizationIdAndAssetModelIdAndLifecycleStateAndArchivedAtIsNullOrderByUnitNumberAsc(
            UUID organizationId, UUID assetModelId, LifecycleState lifecycleState);

    /**
     * True when {@code assetId} is missing a value for at least one active custom field defined on
     * its own model (specification section 7.2: "Adding a field to a model with existing units
     * marks those units metadata incomplete until populated").
     */
    @Query("""
            SELECT CASE WHEN COUNT(f) > 0 THEN true ELSE false END
            FROM ModelCustomField f
            WHERE f.assetModelId = :assetModelId
              AND f.archivedAt IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM AssetCustomFieldValue v
                  WHERE v.assetId = :assetId AND v.modelCustomFieldId = f.id
              )
            """)
    boolean isMetadataIncomplete(@Param("assetId") UUID assetId, @Param("assetModelId") UUID assetModelId);
}

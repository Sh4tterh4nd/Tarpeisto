package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.LifecycleState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<Asset> findWithLockByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Asset> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Asset> findByOrganizationIdAndPublicCode(UUID organizationId, String publicCode);

    /**
     * Reads the immutable-at-request-time values needed to render labels in one organization-scoped
     * query. Historical assets, models, and categories intentionally remain eligible for reprints.
     */
    @Query("""
            SELECT a.id AS assetId,
                   a.publicCode AS publicCode,
                   model.name AS modelName,
                   a.individualName AS individualName,
                   a.unitNumber AS unitNumber,
                   category.name AS categoryName,
                   category.color AS categoryColor
            FROM Asset a
            JOIN AssetModel model
                ON model.id = a.assetModelId AND model.organizationId = a.organizationId
            JOIN Category category
                ON category.id = model.categoryId AND category.organizationId = a.organizationId
            WHERE a.organizationId = :organizationId AND a.id IN :assetIds
            """)
    List<AssetLabelProjection> findLabelProjectionsByOrganizationIdAndIdIn(
            @Param("organizationId") UUID organizationId, @Param("assetIds") List<UUID> assetIds);

    /** Used as {@code AssetCodeUniquenessChecker} (a method reference) by {@code AssetService}. */
    boolean existsByOrganizationIdAndPublicCode(UUID organizationId, String publicCode);

    boolean existsByOrganizationIdAndAssetModelId(UUID organizationId, UUID assetModelId);

    boolean existsByOrganizationIdAndParentContainerAssetId(UUID organizationId, UUID parentContainerAssetId);

    boolean existsByOrganizationIdAndDirectLocationId(UUID organizationId, UUID directLocationId);

    List<Asset> findAllByOrganizationIdAndParentContainerAssetIdOrderByUnitNumberAsc(
            UUID organizationId, UUID parentContainerAssetId);

    @Query("""
            SELECT CASE WHEN COUNT(child) > 0 THEN true ELSE false END
            FROM Asset child JOIN Asset parent ON child.parentContainerAssetId = parent.id
            WHERE parent.organizationId = :organizationId AND parent.assetModelId = :assetModelId
            """)
    boolean hasContainedAssetsForContainerModel(
            @Param("organizationId") UUID organizationId, @Param("assetModelId") UUID assetModelId);

    @Query("""
            SELECT CASE WHEN COUNT(balance) > 0 THEN true ELSE false END
            FROM ConsumableStock balance JOIN Asset container ON balance.containerAssetId = container.id
            WHERE container.organizationId = :organizationId AND container.assetModelId = :assetModelId
            """)
    boolean hasConsumableBalancesForContainerModel(
            @Param("organizationId") UUID organizationId, @Param("assetModelId") UUID assetModelId);

    @Query("""
            SELECT CASE WHEN COUNT(requirement) > 0 THEN true ELSE false END
            FROM PackingRequirement requirement JOIN Asset container ON requirement.containerAssetId = container.id
            WHERE container.organizationId = :organizationId AND container.assetModelId = :assetModelId AND requirement.archivedAt IS NULL
            """)
    boolean hasActivePackingRequirementsForContainerModel(
            @Param("organizationId") UUID organizationId, @Param("assetModelId") UUID assetModelId);

    List<Asset> findAllByOrganizationIdAndAssetModelIdOrderByUnitNumberAsc(UUID organizationId, UUID assetModelId);

    List<Asset> findAllByOrganizationId(UUID organizationId);

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

    interface AssetLabelProjection {

        UUID getAssetId();

        String getPublicCode();

        String getModelName();

        String getIndividualName();

        int getUnitNumber();

        String getCategoryName();

        String getCategoryColor();
    }
}

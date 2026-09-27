package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.LifecycleState;
import io.kellermann.bigcontainers.model.PackingRequirementType;
import io.kellermann.bigcontainers.model.TrackingMode;
import java.math.BigDecimal;
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
            LEFT JOIN Category category
                ON category.id = model.categoryId AND category.organizationId = a.organizationId
            WHERE a.organizationId = :organizationId AND a.id IN :assetIds
              AND model.canContainAssets = false
            """)
    List<AssetLabelProjection> findLabelProjectionsByOrganizationIdAndIdIn(
            @Param("organizationId") UUID organizationId, @Param("assetIds") List<UUID> assetIds);

    /**
     * Compact, organization-safe reporting projections used to take a packing-sheet snapshot.
     * They deliberately join each tenant-owned relation through the same organization id: a
     * caller therefore receives the same not-found result for a foreign asset as for a missing
     * one, and document rendering never follows a JPA relationship.
     */
    @Query("""
            SELECT a.id AS assetId, a.publicCode AS publicCode, a.individualName AS individualName,
                   a.unitNumber AS unitNumber, model.name AS modelName, model.description AS modelDescription,
                   model.canContainAssets AS canContainAssets,
                   category.name AS categoryName, category.color AS categoryColor
            FROM Asset a
            JOIN AssetModel model ON model.id = a.assetModelId AND model.organizationId = a.organizationId
            LEFT JOIN Category category ON category.id = model.categoryId AND category.organizationId = a.organizationId
            WHERE a.organizationId = :organizationId AND a.id = :assetId
            """)
    java.util.Optional<PackingSheetContainerProjection> findPackingSheetContainerProjection(
            @Param("organizationId") UUID organizationId, @Param("assetId") UUID assetId);

    @Query("""
            SELECT requirement.id AS requirementId, requirement.requirementType AS requirementType,
                   requirement.requiredQuantity AS requiredQuantity, requirement.displayOrder AS displayOrder,
                   COALESCE(model.id, specificModel.id) AS assetModelId,
                   COALESCE(model.name, specificModel.name) AS modelName,
                   COALESCE(model.trackingMode, specificModel.trackingMode) AS trackingMode,
                   model.stockUnitLabel AS stockUnitLabel, specific.publicCode AS specificAssetCode,
                   specific.individualName AS specificAssetName, specific.unitNumber AS specificAssetUnitNumber
            FROM PackingRequirement requirement
            LEFT JOIN AssetModel model
                ON model.id = requirement.assetModelId AND model.organizationId = requirement.organizationId
            LEFT JOIN Asset specific
                ON specific.id = requirement.specificAssetId AND specific.organizationId = requirement.organizationId
            LEFT JOIN AssetModel specificModel
                ON specificModel.id = specific.assetModelId AND specificModel.organizationId = requirement.organizationId
            WHERE requirement.organizationId = :organizationId
              AND requirement.containerAssetId = :containerAssetId
              AND requirement.archivedAt IS NULL
            ORDER BY requirement.displayOrder ASC, requirement.id ASC
            """)
    List<PackingSheetRequirementProjection> findActivePackingSheetRequirements(
            @Param("organizationId") UUID organizationId, @Param("containerAssetId") UUID containerAssetId);

    @Query("""
            SELECT child.id AS assetId, child.publicCode AS publicCode, child.individualName AS individualName,
                   child.unitNumber AS unitNumber, model.name AS modelName
            FROM Asset child
            JOIN AssetModel model ON model.id = child.assetModelId AND model.organizationId = child.organizationId
            WHERE child.organizationId = :organizationId
              AND child.parentContainerAssetId = :containerAssetId
              AND model.canContainAssets = true
            ORDER BY model.name ASC, child.individualName ASC NULLS LAST, child.unitNumber ASC, child.publicCode ASC
            """)
    List<PackingSheetChildContainerProjection> findPackingSheetChildContainers(
            @Param("organizationId") UUID organizationId, @Param("containerAssetId") UUID containerAssetId);

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

    interface PackingSheetContainerProjection {
        UUID getAssetId();

        String getPublicCode();

        String getIndividualName();

        int getUnitNumber();

        String getModelName();

        String getModelDescription();

        boolean getCanContainAssets();

        String getCategoryName();

        String getCategoryColor();
    }

    interface PackingSheetRequirementProjection {
        UUID getRequirementId();

        PackingRequirementType getRequirementType();

        BigDecimal getRequiredQuantity();

        int getDisplayOrder();

        UUID getAssetModelId();

        String getModelName();

        TrackingMode getTrackingMode();

        String getStockUnitLabel();

        String getSpecificAssetCode();

        String getSpecificAssetName();

        Integer getSpecificAssetUnitNumber();
    }

    interface PackingSheetChildContainerProjection {
        UUID getAssetId();

        String getPublicCode();

        String getIndividualName();

        int getUnitNumber();

        String getModelName();
    }
}

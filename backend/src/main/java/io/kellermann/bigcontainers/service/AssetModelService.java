package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.exception.NotFoundException;
import io.kellermann.bigcontainers.exception.ValidationFailedException;
import io.kellermann.bigcontainers.model.AssetModel;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.model.TrackingMode;
import io.kellermann.bigcontainers.repository.AssetModelRepository;
import io.kellermann.bigcontainers.repository.AssetRepository;
import io.kellermann.bigcontainers.repository.ConsumableStockRepository;
import io.kellermann.bigcontainers.repository.ModelCustomFieldRepository;
import io.kellermann.bigcontainers.repository.OrganizationRepository;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asset model administration (specification section 6). Every read is scoped to {@code
 * principal.organizationId()}; every mutation additionally requires Owner or Deputy, re-checked
 * here rather than in a URL matcher or the controller, per docs/DEVELOPMENT_POLICIES.md section
 * 5.1 - the same pattern {@link UserService} follows.
 *
 * <p>Rules enforced here, on top of what {@link AssetModel} itself already guards (its own
 * quantity/container-capable and stock-field-scope invariants):
 *
 * <ul>
 *   <li>The referenced category must belong to the same organization and must not be archived
 *       when first selected (specification section 5); this is delegated to {@link
 *       CategoryService#requireActiveCategoryForSelection}.
 *   <li>Switching a model to {@code QUANTITY_STOCK} while it still has custom field definitions is
 *       rejected here with a clear message before it ever reaches the database - the {@code
 *       tr_asset_model_reject_quantity_mode_with_custom_fields} trigger in {@code
 *       V4__create_catalog_schema.sql} remains the authoritative backstop.
 *   <li>Changing tracking mode once dependent data exists (assets, stock balances, packing
 *       requirements, bookings, or history - specification section 6.2) is only partly enforceable
 *       so far: {@link #requireNoTrackingModeDependencyBlocksChange} covers physical assets (Phase
 *       2b part 1, backed by the {@code tr_asset_model_reject_quantity_mode_with_assets} trigger in
 *       {@code V5__create_asset_schema.sql}) and consumable stock balances (Phase 2b part 2, backed
 *       by {@code tr_asset_model_reject_non_quantity_mode_with_stock_balances} in {@code
 *       V6__create_consumable_stock_schema.sql}), on top of the model custom field check that
 *       already existed. Packing requirements, bookings, and history remain a later phase's
 *       extension of this same seam.
 *   <li>Disabling container capability while units currently contain assets or have active packing
 *       requirements (specification section 6.3) still cannot be enforced: {@code physical_asset}
 *       exists as of Phase 2b, but the containment relationship itself (a nullable parent-container
 *       column on {@code physical_asset}) is Phase 4's subject, and {@code packing_requirement} is
 *       Phase 5's. This is called out here and in the task report rather than silently skipped.
 * </ul>
 */
@Service
public class AssetModelService {

    private final AssetModelRepository assetModelRepository;
    private final ModelCustomFieldRepository modelCustomFieldRepository;
    private final AssetRepository assetRepository;
    private final ConsumableStockRepository consumableStockRepository;
    private final CategoryService categoryService;
    private final OrganizationRepository organizationRepository;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    public AssetModelService(
            AssetModelRepository assetModelRepository,
            ModelCustomFieldRepository modelCustomFieldRepository,
            AssetRepository assetRepository,
            ConsumableStockRepository consumableStockRepository,
            CategoryService categoryService,
            OrganizationRepository organizationRepository,
            ActivityLogService activityLogService,
            Clock clock) {
        this.assetModelRepository = assetModelRepository;
        this.modelCustomFieldRepository = modelCustomFieldRepository;
        this.assetRepository = assetRepository;
        this.consumableStockRepository = consumableStockRepository;
        this.categoryService = categoryService;
        this.organizationRepository = organizationRepository;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AssetModelView> list(BigContainersPrincipal principal) {
        requireAuthenticated(principal);
        return assetModelRepository.findAllByOrganizationIdOrderByNameAsc(principal.organizationId()).stream()
                .map(AssetModelView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AssetModelView get(BigContainersPrincipal principal, UUID assetModelId) {
        requireAuthenticated(principal);
        return AssetModelView.from(requireAssetModel(principal.organizationId(), assetModelId));
    }

    @Transactional
    public AssetModelView create(
            BigContainersPrincipal principal,
            String name,
            String description,
            UUID categoryId,
            String replacementUrl,
            TrackingMode trackingMode,
            String stockUnitLabel,
            BigDecimal lowStockThreshold,
            boolean canContainAssets) {
        requireOwnerOrDeputy(principal);
        categoryService.requireActiveCategoryForSelection(principal.organizationId(), categoryId);
        requireNameAvailable(principal.organizationId(), name);
        requireValidReplacementUrl(replacementUrl);

        var now = clock.instant();
        AssetModel assetModel;
        try {
            assetModel = new AssetModel(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    name,
                    description,
                    categoryId,
                    replacementUrl,
                    trackingMode,
                    stockUnitLabel,
                    lowStockThreshold,
                    canContainAssets,
                    now);
        } catch (IllegalArgumentException invalid) {
            // Translates the entity's own cross-field invariant checks (specification section
            // 6.2) into the stable ValidationFailedException/400 contract instead of an unmapped
            // IllegalArgumentException, which ApplicationExceptionHandler does not handle and
            // would otherwise surface as an opaque 500.
            throw new ValidationFailedException(invalid.getMessage());
        }
        assetModelRepository.save(assetModel);

        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MODEL_CREATED",
                "ASSET_MODEL",
                assetModel.getId(),
                Map.of("name", assetModel.getName(), "trackingMode", trackingMode.name()));
        return AssetModelView.from(assetModel);
    }

    @Transactional
    public AssetModelView rename(BigContainersPrincipal principal, UUID assetModelId, String name, String description) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel = requireAssetModel(principal.organizationId(), assetModelId);
        if (!assetModel.getName().equalsIgnoreCase(name)) {
            requireNameAvailable(principal.organizationId(), name);
        }
        assetModel.rename(name, description, clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MODEL_RENAMED",
                "ASSET_MODEL",
                assetModel.getId(),
                Map.of("name", assetModel.getName()));
        return AssetModelView.from(assetModel);
    }

    @Transactional
    public AssetModelView changeCategory(BigContainersPrincipal principal, UUID assetModelId, UUID categoryId) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel = requireAssetModel(principal.organizationId(), assetModelId);
        categoryService.requireActiveCategoryForSelection(principal.organizationId(), categoryId);
        assetModel.changeCategory(categoryId, clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MODEL_CATEGORY_CHANGED",
                "ASSET_MODEL",
                assetModel.getId(),
                Map.of("categoryId", categoryId.toString()));
        return AssetModelView.from(assetModel);
    }

    @Transactional
    public AssetModelView changeReplacementUrl(
            BigContainersPrincipal principal, UUID assetModelId, String replacementUrl) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel = requireAssetModel(principal.organizationId(), assetModelId);
        requireValidReplacementUrl(replacementUrl);
        assetModel.changeReplacementUrl(replacementUrl, clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MODEL_REPLACEMENT_URL_CHANGED",
                "ASSET_MODEL",
                assetModel.getId(),
                null);
        return AssetModelView.from(assetModel);
    }

    @Transactional
    public AssetModelView changeTrackingMode(
            BigContainersPrincipal principal,
            UUID assetModelId,
            TrackingMode newTrackingMode,
            String stockUnitLabel,
            BigDecimal lowStockThreshold) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel = requireAssetModel(principal.organizationId(), assetModelId);
        if (assetModel.getTrackingMode() != newTrackingMode) {
            requireNoTrackingModeDependencyBlocksChange(assetModel);
        }
        try {
            assetModel.changeTrackingMode(newTrackingMode, stockUnitLabel, lowStockThreshold, clock.instant());
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException(invalid.getMessage());
        }
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MODEL_TRACKING_MODE_CHANGED",
                "ASSET_MODEL",
                assetModel.getId(),
                Map.of("trackingMode", newTrackingMode.name()));
        return AssetModelView.from(assetModel);
    }

    @Transactional
    public AssetModelView setCanContainAssets(
            BigContainersPrincipal principal, UUID assetModelId, boolean canContainAssets) {
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        AssetModel assetModel = requireAssetModel(principal.organizationId(), assetModelId);
        if (assetModel.isCanContainAssets()
                && !canContainAssets
                && assetRepository.hasContainedAssetsForContainerModel(principal.organizationId(), assetModelId)) {
            throw new ValidationFailedException(
                    "Containment cannot be disabled while one of this model's assets contains inventory.");
        }
        if (assetModel.isCanContainAssets()
                && !canContainAssets
                && assetRepository.hasConsumableBalancesForContainerModel(principal.organizationId(), assetModelId)) {
            throw new ValidationFailedException(
                    "Containment cannot be disabled while one of this model's assets holds consumable stock.");
        }
        // Specification section 6.3: disabling containment is prohibited while any unit of the
        // model currently contains assets or has active packing requirements. Neither
        // physical_asset nor packing_requirement exists yet (Phase 2b); once they do, the guard
        // belongs here, mirroring requireNoTrackingModeDependencyBlocksChange below.
        try {
            assetModel.setCanContainAssets(canContainAssets, clock.instant());
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException(invalid.getMessage());
        }
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MODEL_CAN_CONTAIN_ASSETS_CHANGED",
                "ASSET_MODEL",
                assetModel.getId(),
                Map.of("canContainAssets", canContainAssets));
        return AssetModelView.from(assetModel);
    }

    @Transactional
    public void archive(BigContainersPrincipal principal, UUID assetModelId) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel = requireAssetModel(principal.organizationId(), assetModelId);
        assetModel.archive(clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MODEL_ARCHIVED",
                "ASSET_MODEL",
                assetModel.getId(),
                null);
    }

    @Transactional
    public void restore(BigContainersPrincipal principal, UUID assetModelId) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel = requireAssetModel(principal.organizationId(), assetModelId);
        assetModel.restore(clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MODEL_RESTORED",
                "ASSET_MODEL",
                assetModel.getId(),
                null);
    }

    /**
     * Resolves an active, organization-scoped asset model for {@code ModelCustomFieldService} to
     * reference, additionally requiring {@link TrackingMode#SERIALIZED_ASSET} (specification
     * section 7: "Quantity-tracked models cannot define these fields"). A thin, differently-named
     * alias of {@link #requireSerializedAssetModel} kept so the call site reads clearly.
     */
    @Transactional(readOnly = true)
    AssetModel requireSerializedAssetModelForCustomFields(UUID organizationId, UUID assetModelId) {
        return requireSerializedAssetModel(organizationId, assetModelId);
    }

    /**
     * Resolves an organization-scoped {@link TrackingMode#SERIALIZED_ASSET} asset model, for any
     * service whose data only ever makes sense against a serialized model: model-defined custom
     * field definitions ({@code ModelCustomFieldService}, specification section 7) and, since
     * Phase 2b, physical assets themselves ({@code AssetService}, specification section 6.2: "Each
     * real-world unit is a physical asset" only for {@code SERIALIZED_ASSET}).
     */
    @Transactional(readOnly = true)
    AssetModel requireSerializedAssetModel(UUID organizationId, UUID assetModelId) {
        AssetModel assetModel = requireAssetModel(organizationId, assetModelId);
        if (assetModel.isQuantityTracked()) {
            throw new ValidationFailedException(
                    "A QUANTITY_STOCK asset model cannot have physical assets or define custom fields.");
        }
        return assetModel;
    }

    /**
     * Resolves an organization-scoped {@link TrackingMode#QUANTITY_STOCK} asset model, for {@code
     * ConsumableStockService} (specification section 6.4: "A quantity-stock balance belongs to ...
     * one quantity-tracked model"; a {@code SERIALIZED_ASSET} model must never have one). Mirrors
     * {@link #requireSerializedAssetModel} from the other tracking mode.
     */
    @Transactional(readOnly = true)
    AssetModel requireQuantityStockAssetModel(UUID organizationId, UUID assetModelId) {
        AssetModel assetModel = requireAssetModel(organizationId, assetModelId);
        if (!assetModel.isQuantityTracked()) {
            throw new ValidationFailedException("A SERIALIZED_ASSET model must never have a consumable stock balance.");
        }
        return assetModel;
    }

    private AssetModel requireAssetModel(UUID organizationId, UUID assetModelId) {
        return assetModelRepository
                .findByIdAndOrganizationId(assetModelId, organizationId)
                .orElseThrow(AssetModelService::assetModelNotFound);
    }

    private void lockOrganization(UUID organizationId) {
        organizationRepository
                .findWithLockById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private void requireNoTrackingModeDependencyBlocksChange(AssetModel assetModel) {
        if (modelCustomFieldRepository.existsByAssetModelId(assetModel.getId())) {
            throw new ValidationFailedException(
                    "Cannot change tracking mode while this model has custom field definitions.");
        }
        if (assetRepository.existsByOrganizationIdAndAssetModelId(assetModel.getOrganizationId(), assetModel.getId())) {
            throw new ValidationFailedException("Cannot change tracking mode while this model has physical assets.");
        }
        if (consumableStockRepository.existsByOrganizationIdAndAssetModelId(
                assetModel.getOrganizationId(), assetModel.getId())) {
            throw new ValidationFailedException("Cannot change tracking mode while this model has stock balances.");
        }
    }

    private void requireNameAvailable(UUID organizationId, String name) {
        if (assetModelRepository
                .findByOrganizationIdAndNameIgnoreCaseAndArchivedAtIsNull(
                        organizationId, name == null ? "" : name.trim())
                .isPresent()) {
            throw new ValidationFailedException("An asset model with this name already exists.");
        }
    }

    private void requireValidReplacementUrl(String replacementUrl) {
        if (replacementUrl == null || replacementUrl.isBlank()) {
            return;
        }
        try {
            URI uri = new URI(replacementUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new ValidationFailedException("replacementUrl must be an absolute HTTP or HTTPS URL.");
            }
        } catch (URISyntaxException exception) {
            throw new ValidationFailedException("replacementUrl must be a valid URL.");
        }
    }

    private static NotFoundException assetModelNotFound() {
        return new NotFoundException("Asset model not found.");
    }

    private void requireOwnerOrDeputy(BigContainersPrincipal principal) {
        if (principal == null
                || (principal.role() != OrganizationRole.OWNER && principal.role() != OrganizationRole.DEPUTY)) {
            throw new AccessDeniedException("Owner or Deputy role required.");
        }
    }

    private void requireAuthenticated(BigContainersPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Authentication required.");
        }
    }
}

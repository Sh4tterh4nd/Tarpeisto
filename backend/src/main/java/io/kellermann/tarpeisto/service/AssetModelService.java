package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.AssetModel;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.repository.AssetModelRepository;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.ConsumableStockRepository;
import io.kellermann.tarpeisto.repository.ModelCustomFieldRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.repository.PackingRequirementHistoryRepository;
import io.kellermann.tarpeisto.repository.PackingRequirementRepository;
import io.kellermann.tarpeisto.repository.PackingTemplateRequirementRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
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
 *       requirements, bookings, or history - specification section 6.2) is rejected. Booking lines
 *       reference an asset or stock balance, so those existing dependency checks also preserve
 *       current and historical booking references.
 *   <li>Disabling container capability while units contain assets, consumable stock, or active
 *       packing requirements is rejected under the same organization lock used for placement and
 *       packing mutations.
 * </ul>
 */
@Service
public class AssetModelService {

    private final AssetModelRepository assetModelRepository;
    private final ModelCustomFieldRepository modelCustomFieldRepository;
    private final AssetRepository assetRepository;
    private final ConsumableStockRepository consumableStockRepository;
    private final PackingRequirementRepository packingRequirementRepository;
    private final PackingTemplateRequirementRepository packingTemplateRequirementRepository;
    private final PackingRequirementHistoryRepository packingRequirementHistoryRepository;
    private final CategoryService categoryService;
    private final OrganizationRepository organizationRepository;
    private final ActivityLogService activityLogService;
    private final BookingImpactService bookingImpact;
    private final Clock clock;

    public AssetModelService(
            AssetModelRepository assetModelRepository,
            ModelCustomFieldRepository modelCustomFieldRepository,
            AssetRepository assetRepository,
            ConsumableStockRepository consumableStockRepository,
            PackingRequirementRepository packingRequirementRepository,
            PackingTemplateRequirementRepository packingTemplateRequirementRepository,
            PackingRequirementHistoryRepository packingRequirementHistoryRepository,
            CategoryService categoryService,
            OrganizationRepository organizationRepository,
            ActivityLogService activityLogService,
            BookingImpactService bookingImpact,
            Clock clock) {
        this.assetModelRepository = assetModelRepository;
        this.modelCustomFieldRepository = modelCustomFieldRepository;
        this.assetRepository = assetRepository;
        this.consumableStockRepository = consumableStockRepository;
        this.packingRequirementRepository = packingRequirementRepository;
        this.packingTemplateRequirementRepository = packingTemplateRequirementRepository;
        this.packingRequirementHistoryRepository = packingRequirementHistoryRepository;
        this.categoryService = categoryService;
        this.organizationRepository = organizationRepository;
        this.activityLogService = activityLogService;
        this.bookingImpact = bookingImpact;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AssetModelView> list(TarpeistoPrincipal principal) {
        return list(principal, false);
    }

    @Transactional(readOnly = true)
    public List<AssetModelView> list(TarpeistoPrincipal principal, boolean includeArchived) {
        if (principal != null) principal.requirePermanent();
        requireAuthenticated(principal);
        return assetModelRepository.findAllByOrganizationIdOrderByNameAsc(principal.organizationId()).stream()
                .filter(row -> includeArchived || !row.isArchived())
                .map(AssetModelView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AssetModelView get(TarpeistoPrincipal principal, UUID assetModelId) {
        if (principal != null) principal.requirePermanent();
        requireAuthenticated(principal);
        return AssetModelView.from(requireAssetModel(principal.organizationId(), assetModelId));
    }

    @Transactional
    public AssetModelView create(
            TarpeistoPrincipal principal,
            String name,
            String description,
            UUID categoryId,
            String replacementUrl,
            TrackingMode trackingMode,
            String stockUnitLabel,
            BigDecimal lowStockThreshold,
            boolean canContainAssets) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        if (categoryId != null) {
            categoryService.requireActiveCategoryForSelection(principal.organizationId(), categoryId);
        }
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
    public AssetModelView rename(TarpeistoPrincipal principal, UUID assetModelId, String name, String description) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
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
    public AssetModelView changeCategory(TarpeistoPrincipal principal, UUID assetModelId, UUID categoryId) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        AssetModel assetModel = requireAssetModel(principal.organizationId(), assetModelId);
        if (categoryId != null) {
            categoryService.requireActiveCategoryForSelection(principal.organizationId(), categoryId);
        }
        assetModel.changeCategory(categoryId, clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MODEL_CATEGORY_CHANGED",
                "ASSET_MODEL",
                assetModel.getId(),
                categoryId == null ? Map.of("category", "Default") : Map.of("categoryId", categoryId.toString()));
        return AssetModelView.from(assetModel);
    }

    @Transactional
    public AssetModelView changeReplacementUrl(TarpeistoPrincipal principal, UUID assetModelId, String replacementUrl) {
        if (principal != null) principal.requirePermanent();
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
            TarpeistoPrincipal principal,
            UUID assetModelId,
            TrackingMode newTrackingMode,
            String stockUnitLabel,
            BigDecimal lowStockThreshold) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
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
            TarpeistoPrincipal principal, UUID assetModelId, boolean canContainAssets) {
        if (principal != null) principal.requirePermanent();
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
        if (assetModel.isCanContainAssets()
                && !canContainAssets
                && assetRepository.hasActivePackingRequirementsForContainerModel(
                        principal.organizationId(), assetModelId)) {
            throw new ValidationFailedException(
                    "Containment cannot be disabled while one of this model's assets has packing requirements.");
        }
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
        assetModelRepository.flush();
        bookingImpact.changed(principal);
        return AssetModelView.from(assetModel);
    }

    @Transactional
    public void archive(TarpeistoPrincipal principal, UUID assetModelId) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        AssetModel assetModel = requireAssetModel(principal.organizationId(), assetModelId);
        assetModel.archive(clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MODEL_ARCHIVED",
                "ASSET_MODEL",
                assetModel.getId(),
                null);
        assetModelRepository.flush();
        bookingImpact.changed(principal);
    }

    @Transactional
    public void restore(TarpeistoPrincipal principal, UUID assetModelId) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        AssetModel assetModel = requireAssetModel(principal.organizationId(), assetModelId);
        assetModel.restore(clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MODEL_RESTORED",
                "ASSET_MODEL",
                assetModel.getId(),
                null);
        assetModelRepository.flush();
        bookingImpact.changed(principal);
    }

    /**
     * Resolves an active, organization-scoped asset model for {@code ModelCustomFieldService} to
     * reference, additionally requiring {@link TrackingMode#SERIALIZED_ASSET} (specification
     * section 7: "Quantity-tracked models cannot define these fields"). A thin, differently-named
     * alias of {@link #requireSerializedAssetModel} kept so the call site reads clearly.
     */
    @Transactional(readOnly = true)
    AssetModel requireSerializedAssetModelForCustomFields(UUID organizationId, UUID assetModelId) {
        return requireActiveSerializedAssetModelForSelection(organizationId, assetModelId);
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

    /** New selections serialize with archival; historical resolvers remain readable. */
    @Transactional
    AssetModel requireActiveSerializedAssetModelForSelection(UUID organizationId, UUID assetModelId) {
        lockOrganization(organizationId);
        return requireActiveForSelection(requireSerializedAssetModel(organizationId, assetModelId));
    }

    @Transactional
    AssetModel requireActiveQuantityStockAssetModelForSelection(UUID organizationId, UUID assetModelId) {
        lockOrganization(organizationId);
        return requireActiveForSelection(requireQuantityStockAssetModel(organizationId, assetModelId));
    }

    private AssetModel requireActiveForSelection(AssetModel model) {
        if (model.isArchived()) {
            throw new ValidationFailedException("An archived asset model cannot be selected. Restore it first.");
        }
        return model;
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
        if (packingRequirementRepository.existsByOrganizationIdAndAssetModelId(
                        assetModel.getOrganizationId(), assetModel.getId())
                || packingTemplateRequirementRepository.existsByOrganizationIdAndAssetModelId(
                        assetModel.getOrganizationId(), assetModel.getId())
                || packingRequirementHistoryRepository.hasAssetModelHistory(
                        assetModel.getOrganizationId(), assetModel.getId())) {
            throw new ValidationFailedException(
                    "Cannot change tracking mode while this model has packing requirements or their history.");
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

    private void requireOwnerOrDeputy(TarpeistoPrincipal principal) {
        if (principal == null
                || (principal.role() != OrganizationRole.OWNER && principal.role() != OrganizationRole.DEPUTY)) {
            throw new AccessDeniedException("Owner or Deputy role required.");
        }
    }

    private void requireAuthenticated(TarpeistoPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Authentication required.");
        }
    }
}

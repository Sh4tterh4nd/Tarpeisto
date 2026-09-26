package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.exception.InvalidAssetCodeException;
import io.kellermann.bigcontainers.exception.NotFoundException;
import io.kellermann.bigcontainers.exception.ValidationFailedException;
import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.AssetCode;
import io.kellermann.bigcontainers.model.AssetCodeValidation;
import io.kellermann.bigcontainers.model.AssetCustomFieldValue;
import io.kellermann.bigcontainers.model.AssetModel;
import io.kellermann.bigcontainers.model.AssetStateChange;
import io.kellermann.bigcontainers.model.AssetStateChangeType;
import io.kellermann.bigcontainers.model.Condition;
import io.kellermann.bigcontainers.model.CustomFieldDataType;
import io.kellermann.bigcontainers.model.LifecycleState;
import io.kellermann.bigcontainers.model.ModelCustomField;
import io.kellermann.bigcontainers.model.ModelCustomFieldOption;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.repository.AssetCustomFieldValueRepository;
import io.kellermann.bigcontainers.repository.AssetRepository;
import io.kellermann.bigcontainers.repository.AssetStateChangeRepository;
import io.kellermann.bigcontainers.repository.AssetUnitNumberSequenceRepository;
import io.kellermann.bigcontainers.repository.ModelCustomFieldOptionRepository;
import io.kellermann.bigcontainers.repository.ModelCustomFieldRepository;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Physical asset administration (specification section 8), the model-defined custom field values
 * that live only on those assets (specification section 7), and their append-only condition/
 * lifecycle history (specification sections 8.3/8.4/25).
 *
 * <p>Every read is scoped to {@code principal.organizationId()}; every mutation additionally
 * requires Owner or Deputy (task scope: "Owners and Deputies administer assets; Operator/Auditor
 * and Viewer cannot mutate them"), re-checked here rather than in a URL matcher or the controller,
 * per docs/DEVELOPMENT_POLICIES.md section 5.1 - the same pattern {@link AssetModelService}/{@link
 * CategoryService} follow.
 *
 * <p><strong>Only a {@code SERIALIZED_ASSET} model may have physical assets</strong>
 * (specification section 6.2): enforced here via {@link
 * AssetModelService#requireSerializedAssetModel}, and a second time by the {@code
 * tr_physical_asset_reject_quantity_model} database trigger.
 *
 * <p><strong>Required custom field values</strong> (specification section 7.2: "New asset creation
 * cannot complete while a required value is missing") are enforced by {@link #create} for a single,
 * manually created unit, which must supply a value for every currently active custom field of its
 * model. {@link #createBulk} deliberately does <em>not</em> take per-unit values - specification
 * section 8.2 describes bulk creation purely in terms of "sequential model-local unit numbers", and
 * threading distinct per-unit values (a different serial number for each of, say, fifty units)
 * through one bulk-creation call is a separate UI concern. A bulk-created unit is therefore
 * "metadata incomplete" (specification section 7.2) from the moment it is created whenever its
 * model has any active custom field, exactly like an older unit after a new field is added to its
 * model - {@link #setValues} is how either case gets completed afterward.
 *
 * <p><strong>Metadata incompleteness</strong> is never stored: {@link
 * AssetRepository#isMetadataIncomplete} recomputes it on every read as "at least one of this
 * asset's model's currently active custom fields has no matching {@code AssetCustomFieldValue}
 * row", so it is automatically correct after a field is archived, added, or a value is filled in -
 * there is no cache to keep in sync.
 */
@Service
public class AssetService {

    private final AssetRepository assetRepository;
    private final AssetCustomFieldValueRepository assetCustomFieldValueRepository;
    private final AssetStateChangeRepository assetStateChangeRepository;
    private final AssetUnitNumberSequenceRepository unitNumberSequenceRepository;
    private final ModelCustomFieldRepository modelCustomFieldRepository;
    private final ModelCustomFieldOptionRepository modelCustomFieldOptionRepository;
    private final AssetModelService assetModelService;
    private final AssetCodeGenerationService assetCodeGenerationService;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    public AssetService(
            AssetRepository assetRepository,
            AssetCustomFieldValueRepository assetCustomFieldValueRepository,
            AssetStateChangeRepository assetStateChangeRepository,
            AssetUnitNumberSequenceRepository unitNumberSequenceRepository,
            ModelCustomFieldRepository modelCustomFieldRepository,
            ModelCustomFieldOptionRepository modelCustomFieldOptionRepository,
            AssetModelService assetModelService,
            AssetCodeGenerationService assetCodeGenerationService,
            ActivityLogService activityLogService,
            Clock clock) {
        this.assetRepository = assetRepository;
        this.assetCustomFieldValueRepository = assetCustomFieldValueRepository;
        this.assetStateChangeRepository = assetStateChangeRepository;
        this.unitNumberSequenceRepository = unitNumberSequenceRepository;
        this.modelCustomFieldRepository = modelCustomFieldRepository;
        this.modelCustomFieldOptionRepository = modelCustomFieldOptionRepository;
        this.assetModelService = assetModelService;
        this.assetCodeGenerationService = assetCodeGenerationService;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AssetView> list(BigContainersPrincipal principal, UUID assetModelId, boolean includeInactive) {
        requireAuthenticated(principal);
        AssetModelView model = assetModelService.get(principal, assetModelId);
        List<Asset> assets = includeInactive
                ? assetRepository.findAllByOrganizationIdAndAssetModelIdOrderByUnitNumberAsc(
                        principal.organizationId(), assetModelId)
                : assetRepository
                        .findAllByOrganizationIdAndAssetModelIdAndLifecycleStateAndArchivedAtIsNullOrderByUnitNumberAsc(
                                principal.organizationId(), assetModelId, LifecycleState.ACTIVE);
        return assets.stream().map(asset -> toView(asset, model.name())).toList();
    }

    @Transactional(readOnly = true)
    public AssetView get(BigContainersPrincipal principal, UUID assetId) {
        requireAuthenticated(principal);
        Asset asset = requireAsset(principal.organizationId(), assetId);
        return toView(
                asset, assetModelService.get(principal, asset.getAssetModelId()).name());
    }

    /**
     * Manual public-code lookup (specification section 9.4, ADR-0002 "Validation order"):
     * normalizes and validates the checksum <strong>before</strong> any database lookup, and
     * distinguishes a transcription error ({@link InvalidAssetCodeException}, checksum failed) from
     * an unknown code ({@link NotFoundException}, checksum valid but no matching asset in this
     * organization) - the same distinction a scanner result screen needs.
     */
    @Transactional(readOnly = true)
    public AssetView getByCode(BigContainersPrincipal principal, String rawCode) {
        requireAuthenticated(principal);
        AssetCodeValidation validation = AssetCode.validate(rawCode);
        if (validation instanceof AssetCodeValidation.Invalid invalid) {
            throw new InvalidAssetCodeException(invalid.reason());
        }
        AssetCodeValidation.Valid valid = (AssetCodeValidation.Valid) validation;
        Asset asset = assetRepository
                .findByOrganizationIdAndPublicCode(
                        principal.organizationId(), valid.code().value())
                .orElseThrow(AssetService::assetNotFound);
        return toView(
                asset, assetModelService.get(principal, asset.getAssetModelId()).name());
    }

    @Transactional
    public AssetView create(
            BigContainersPrincipal principal,
            UUID assetModelId,
            String individualName,
            LocalDate purchaseDate,
            List<AssetCustomFieldValueInput> values) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel = assetModelService.requireSerializedAssetModel(principal.organizationId(), assetModelId);
        requireIndividualNameIfContainer(assetModel, individualName);

        List<ModelCustomField> activeFields = activeFields(principal.organizationId(), assetModelId);
        List<AssetCustomFieldValueInput> safeValues = values == null ? List.of() : values;
        requireAllActiveFieldsProvided(activeFields, safeValues);

        var now = clock.instant();
        String publicCode = generatePublicCode(principal.organizationId());
        int unitNumber = unitNumberSequenceRepository.allocateRange(principal.organizationId(), assetModelId, 1);

        Asset asset;
        try {
            asset = new Asset(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    assetModelId,
                    publicCode,
                    unitNumber,
                    individualName,
                    purchaseDate,
                    now);
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException(invalid.getMessage());
        }
        assetRepository.save(asset);

        for (AssetCustomFieldValueInput input : safeValues) {
            saveValue(principal.organizationId(), assetModelId, asset.getId(), activeFields, input, now);
        }

        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_CREATED",
                "ASSET",
                asset.getId(),
                Map.of("assetModelId", assetModelId.toString(), "publicCode", publicCode, "unitNumber", unitNumber));
        return toView(asset, assetModel.getName());
    }

    /**
     * Creates {@code count} new units with sequential model-local unit numbers (specification
     * section 8.2). Deliberately takes no per-unit custom field values - see the class Javadoc.
     * Container-capable models are rejected: specification section 8.2 requires every container
     * asset to have its own individual name, which a numbered bulk operation cannot supply.
     */
    @Transactional
    public List<AssetView> createBulk(
            BigContainersPrincipal principal, UUID assetModelId, int count, LocalDate purchaseDate) {
        requireOwnerOrDeputy(principal);
        if (count < 1) {
            throw new ValidationFailedException("count must be at least 1.");
        }
        AssetModel assetModel = assetModelService.requireSerializedAssetModel(principal.organizationId(), assetModelId);
        if (assetModel.isCanContainAssets()) {
            throw new ValidationFailedException(
                    "Container-capable models cannot be bulk created: each container asset requires its own individual name.");
        }

        var now = clock.instant();
        int startingUnitNumber =
                unitNumberSequenceRepository.allocateRange(principal.organizationId(), assetModelId, count);
        List<AssetView> created = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String publicCode = generatePublicCode(principal.organizationId());
            Asset asset = new Asset(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    assetModelId,
                    publicCode,
                    startingUnitNumber + i,
                    null,
                    purchaseDate,
                    now);
            assetRepository.save(asset);
            activityLogService.record(
                    principal.organizationId(),
                    principal.userId(),
                    "ASSET_CREATED",
                    "ASSET",
                    asset.getId(),
                    Map.of(
                            "assetModelId",
                            assetModelId.toString(),
                            "publicCode",
                            publicCode,
                            "unitNumber",
                            asset.getUnitNumber()));
            created.add(toView(asset, assetModel.getName()));
        }
        return created;
    }

    /** Upserts one or more values on an existing asset; used both to fill in a bulk-created unit's metadata and to edit an existing value. */
    @Transactional
    public AssetView setValues(
            BigContainersPrincipal principal, UUID assetId, List<AssetCustomFieldValueInput> values) {
        requireOwnerOrDeputy(principal);
        Asset asset = requireAsset(principal.organizationId(), assetId);
        List<ModelCustomField> activeFields = activeFields(principal.organizationId(), asset.getAssetModelId());
        var now = clock.instant();
        for (AssetCustomFieldValueInput input : values == null ? List.<AssetCustomFieldValueInput>of() : values) {
            saveValue(principal.organizationId(), asset.getAssetModelId(), asset.getId(), activeFields, input, now);
        }
        activityLogService.record(
                principal.organizationId(), principal.userId(), "ASSET_VALUES_UPDATED", "ASSET", asset.getId(), null);
        return toView(
                asset, assetModelService.get(principal, asset.getAssetModelId()).name());
    }

    @Transactional
    public AssetView rename(BigContainersPrincipal principal, UUID assetId, String individualName) {
        requireOwnerOrDeputy(principal);
        Asset asset = requireAsset(principal.organizationId(), assetId);
        AssetModelView model = assetModelService.get(principal, asset.getAssetModelId());
        if (model.canContainAssets() && (individualName == null || individualName.isBlank())) {
            throw new ValidationFailedException("A container asset requires an individual name.");
        }
        asset.rename(individualName, clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_RENAMED",
                "ASSET",
                asset.getId(),
                Map.of("individualName", String.valueOf(asset.getIndividualName())));
        return toView(asset, model.name());
    }

    @Transactional
    public AssetView changePurchaseDate(BigContainersPrincipal principal, UUID assetId, LocalDate purchaseDate) {
        requireOwnerOrDeputy(principal);
        Asset asset = requireAsset(principal.organizationId(), assetId);
        asset.setPurchaseDate(purchaseDate, clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_PURCHASE_DATE_CHANGED",
                "ASSET",
                asset.getId(),
                null);
        return toView(
                asset, assetModelService.get(principal, asset.getAssetModelId()).name());
    }

    @Transactional
    public AssetView changeCondition(
            BigContainersPrincipal principal, UUID assetId, Condition newCondition, String reason) {
        requireOwnerOrDeputy(principal);
        Asset asset = requireAsset(principal.organizationId(), assetId);
        var now = clock.instant();
        Condition previous;
        try {
            previous = asset.changeCondition(newCondition, now);
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException(invalid.getMessage());
        }
        assetStateChangeRepository.save(new AssetStateChange(
                UUID.randomUUID(),
                principal.organizationId(),
                asset.getId(),
                AssetStateChangeType.CONDITION,
                previous.name(),
                newCondition.name(),
                reason,
                principal.userId(),
                now));
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_CONDITION_CHANGED",
                "ASSET",
                asset.getId(),
                Map.of("previous", previous.name(), "new", newCondition.name()));
        return toView(
                asset, assetModelService.get(principal, asset.getAssetModelId()).name());
    }

    /**
     * Changes lifecycle state, including restoring a lost asset back to {@code ACTIVE}
     * (specification section 8.4: "Scanning a lost asset ... allows an Owner or Deputy to restore
     * it") - restoring is simply setting the lifecycle state back to {@code ACTIVE} through this
     * same method, not a separate endpoint.
     */
    @Transactional
    public AssetView changeLifecycleState(
            BigContainersPrincipal principal, UUID assetId, LifecycleState newLifecycleState, String reason) {
        requireOwnerOrDeputy(principal);
        Asset asset = requireAsset(principal.organizationId(), assetId);
        var now = clock.instant();
        LifecycleState previous;
        try {
            previous = asset.changeLifecycleState(newLifecycleState, now);
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException(invalid.getMessage());
        }
        assetStateChangeRepository.save(new AssetStateChange(
                UUID.randomUUID(),
                principal.organizationId(),
                asset.getId(),
                AssetStateChangeType.LIFECYCLE,
                previous.name(),
                newLifecycleState.name(),
                reason,
                principal.userId(),
                now));
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_LIFECYCLE_CHANGED",
                "ASSET",
                asset.getId(),
                Map.of("previous", previous.name(), "new", newLifecycleState.name()));
        return toView(
                asset, assetModelService.get(principal, asset.getAssetModelId()).name());
    }

    @Transactional(readOnly = true)
    public List<AssetStateChangeView> history(BigContainersPrincipal principal, UUID assetId) {
        requireAuthenticated(principal);
        requireAsset(principal.organizationId(), assetId);
        return assetStateChangeRepository
                .findAllByOrganizationIdAndAssetIdOrderByChangedAtDesc(principal.organizationId(), assetId)
                .stream()
                .map(AssetStateChangeView::from)
                .toList();
    }

    @Transactional
    public void archive(BigContainersPrincipal principal, UUID assetId) {
        requireOwnerOrDeputy(principal);
        Asset asset = requireAsset(principal.organizationId(), assetId);
        asset.archive(clock.instant());
        activityLogService.record(
                principal.organizationId(), principal.userId(), "ASSET_ARCHIVED", "ASSET", asset.getId(), null);
    }

    @Transactional
    public void restore(BigContainersPrincipal principal, UUID assetId) {
        requireOwnerOrDeputy(principal);
        Asset asset = requireAsset(principal.organizationId(), assetId);
        asset.restore(clock.instant());
        activityLogService.record(
                principal.organizationId(), principal.userId(), "ASSET_RESTORED", "ASSET", asset.getId(), null);
    }

    private String generatePublicCode(UUID organizationId) {
        return assetCodeGenerationService
                .generate(organizationId, assetRepository::existsByOrganizationIdAndPublicCode)
                .value();
    }

    private void requireIndividualNameIfContainer(AssetModel assetModel, String individualName) {
        if (assetModel.isCanContainAssets() && (individualName == null || individualName.isBlank())) {
            throw new ValidationFailedException("A container asset requires an individual name.");
        }
    }

    private void saveValue(
            UUID organizationId,
            UUID assetModelId,
            UUID assetId,
            List<ModelCustomField> activeFields,
            AssetCustomFieldValueInput input,
            Instant now) {
        if (input == null || input.fieldId() == null) {
            throw new ValidationFailedException("Each custom field value requires a fieldId.");
        }
        ModelCustomField field = activeFields.stream()
                .filter(candidate -> candidate.getId().equals(input.fieldId()))
                .findFirst()
                .or(() -> modelCustomFieldRepository.findByIdAndOrganizationId(input.fieldId(), organizationId))
                .orElseThrow(() -> new ValidationFailedException("Unknown custom field."));
        if (!field.getAssetModelId().equals(assetModelId)) {
            throw new ValidationFailedException("This custom field does not belong to this asset's model.");
        }
        if (field.isArchived()) {
            throw new ValidationFailedException("Cannot set a value for an archived custom field: " + field.getName());
        }

        UUID validatedOptionId = null;
        if (field.getDataType() == CustomFieldDataType.DROPDOWN) {
            validatedOptionId = requireActiveOptionForField(organizationId, field, input.optionId());
        }

        try {
            Optional<AssetCustomFieldValue> existing =
                    assetCustomFieldValueRepository.findByAssetIdAndModelCustomFieldId(assetId, field.getId());
            if (existing.isPresent()) {
                existing.get()
                        .update(field.getDataType(), input.stringValue(), input.dateValue(), validatedOptionId, now);
            } else {
                assetCustomFieldValueRepository.save(new AssetCustomFieldValue(
                        UUID.randomUUID(),
                        organizationId,
                        assetId,
                        field.getId(),
                        field.getDataType(),
                        input.stringValue(),
                        input.dateValue(),
                        validatedOptionId,
                        now));
            }
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException(invalid.getMessage());
        }
    }

    /**
     * Validates "Dropdown values must reference an active option belonging to the correct
     * definition" (specification section 7.2): the option must exist in this organization, belong
     * to {@code field} (not a different field - a String/Date field never reaches here since this
     * is only called for a {@code DROPDOWN} field), and not be archived.
     */
    private UUID requireActiveOptionForField(UUID organizationId, ModelCustomField field, UUID optionId) {
        if (optionId == null) {
            throw new ValidationFailedException("A DROPDOWN custom field value requires an optionId.");
        }
        ModelCustomFieldOption option = modelCustomFieldOptionRepository
                .findByIdAndOrganizationId(optionId, organizationId)
                .orElseThrow(() -> new ValidationFailedException("Unknown dropdown option."));
        if (!option.getModelCustomFieldId().equals(field.getId())) {
            throw new ValidationFailedException("This option does not belong to the field: " + field.getName());
        }
        if (option.isArchived()) {
            throw new ValidationFailedException("This dropdown option is archived and cannot be selected.");
        }
        return option.getId();
    }

    private List<ModelCustomField> activeFields(UUID organizationId, UUID assetModelId) {
        return modelCustomFieldRepository
                .findAllByOrganizationIdAndAssetModelIdOrderByDisplayOrderAsc(organizationId, assetModelId)
                .stream()
                .filter(field -> !field.isArchived())
                .toList();
    }

    private void requireAllActiveFieldsProvided(
            List<ModelCustomField> activeFields, List<AssetCustomFieldValueInput> values) {
        Set<UUID> providedFieldIds =
                values.stream().map(AssetCustomFieldValueInput::fieldId).collect(Collectors.toSet());
        List<String> missing = activeFields.stream()
                .filter(field -> !providedFieldIds.contains(field.getId()))
                .map(ModelCustomField::getName)
                .toList();
        if (!missing.isEmpty()) {
            throw new ValidationFailedException(
                    "All active custom fields are required: missing " + String.join(", ", missing) + ".");
        }
    }

    private AssetView toView(Asset asset, String assetModelName) {
        boolean metadataIncomplete = assetRepository.isMetadataIncomplete(asset.getId(), asset.getAssetModelId());
        List<AssetCustomFieldValueView> values =
                assetCustomFieldValueRepository
                        .findAllByOrganizationIdAndAssetId(asset.getOrganizationId(), asset.getId())
                        .stream()
                        .map(value -> toValueView(asset.getOrganizationId(), value))
                        .toList();
        String displayName = asset.getIndividualName() != null
                ? asset.getIndividualName()
                : assetModelName + " " + asset.getUnitNumber();
        return new AssetView(
                asset.getId(),
                asset.getAssetModelId(),
                assetModelName,
                asset.getPublicCode(),
                asset.getUnitNumber(),
                asset.getIndividualName(),
                displayName,
                asset.getCondition(),
                asset.getLifecycleState(),
                asset.getPurchaseDate(),
                asset.isArchived(),
                metadataIncomplete,
                values,
                asset.getCreatedAt(),
                asset.getUpdatedAt());
    }

    private AssetCustomFieldValueView toValueView(UUID organizationId, AssetCustomFieldValue value) {
        ModelCustomField field = modelCustomFieldRepository
                .findByIdAndOrganizationId(value.getModelCustomFieldId(), organizationId)
                .orElseThrow(() -> new IllegalStateException("Custom field referenced by a value is missing."));
        String optionValue = null;
        if (value.getOptionId() != null) {
            optionValue = modelCustomFieldOptionRepository
                    .findByIdAndOrganizationId(value.getOptionId(), organizationId)
                    .map(ModelCustomFieldOption::getValue)
                    .orElse(null);
        }
        return new AssetCustomFieldValueView(
                field.getId(),
                field.getName(),
                field.getDataType(),
                field.getDisplayOrder(),
                value.getStringValue(),
                value.getDateValue(),
                value.getOptionId(),
                optionValue);
    }

    private Asset requireAsset(UUID organizationId, UUID assetId) {
        return assetRepository
                .findByIdAndOrganizationId(assetId, organizationId)
                .orElseThrow(AssetService::assetNotFound);
    }

    private static NotFoundException assetNotFound() {
        return new NotFoundException("Asset not found.");
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

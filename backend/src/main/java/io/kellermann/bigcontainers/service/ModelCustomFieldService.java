package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.exception.NotFoundException;
import io.kellermann.bigcontainers.exception.ValidationFailedException;
import io.kellermann.bigcontainers.model.AssetModel;
import io.kellermann.bigcontainers.model.CustomFieldDataType;
import io.kellermann.bigcontainers.model.ModelCustomField;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.repository.AssetCustomFieldValueRepository;
import io.kellermann.bigcontainers.repository.ModelCustomFieldRepository;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Model-defined unit field administration (specification section 7). Definitions live on a
 * {@code SERIALIZED_ASSET} {@link AssetModel} only - {@link
 * AssetModelService#requireSerializedAssetModelForCustomFields} rejects a quantity-tracked model
 * here, and the {@code tr_model_custom_field_reject_quantity_model} database trigger backs that up
 * a second time.
 *
 * <p>Every mutation requires Owner or Deputy, re-checked here (docs/DEVELOPMENT_POLICIES.md
 * section 5.1). Values themselves live on Phase 2b's {@code asset_custom_field_value}; {@link
 * #requireNoValueDependencyBlocksDataTypeChange} enforces "changing a field's datatype after values
 * exist is prohibited" (specification section 7.2) now that table exists.
 */
@Service
public class ModelCustomFieldService {

    private final ModelCustomFieldRepository modelCustomFieldRepository;
    private final AssetCustomFieldValueRepository assetCustomFieldValueRepository;
    private final AssetModelService assetModelService;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    public ModelCustomFieldService(
            ModelCustomFieldRepository modelCustomFieldRepository,
            AssetCustomFieldValueRepository assetCustomFieldValueRepository,
            AssetModelService assetModelService,
            ActivityLogService activityLogService,
            Clock clock) {
        this.modelCustomFieldRepository = modelCustomFieldRepository;
        this.assetCustomFieldValueRepository = assetCustomFieldValueRepository;
        this.assetModelService = assetModelService;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ModelCustomFieldView> list(BigContainersPrincipal principal, UUID assetModelId) {
        requireAuthenticated(principal);
        // A field list is meaningful even for an archived model (historical review), so this does
        // not require the model to still be SERIALIZED_ASSET or active - only that it belongs to
        // the caller's organization.
        assetModelService.get(principal, assetModelId);
        return modelCustomFieldRepository
                .findAllByOrganizationIdAndAssetModelIdOrderByDisplayOrderAsc(principal.organizationId(), assetModelId)
                .stream()
                .map(ModelCustomFieldView::from)
                .toList();
    }

    @Transactional
    public ModelCustomFieldView create(
            BigContainersPrincipal principal, UUID assetModelId, String name, CustomFieldDataType dataType) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel =
                assetModelService.requireSerializedAssetModelForCustomFields(principal.organizationId(), assetModelId);
        requireNameAvailable(assetModelId, name);

        int nextDisplayOrder = modelCustomFieldRepository
                        .findAllByOrganizationIdAndAssetModelIdOrderByDisplayOrderAsc(
                                principal.organizationId(), assetModelId)
                        .stream()
                        .mapToInt(ModelCustomField::getDisplayOrder)
                        .max()
                        .orElse(-1)
                + 1;

        var now = clock.instant();
        ModelCustomField field = new ModelCustomField(
                UUID.randomUUID(),
                principal.organizationId(),
                assetModel.getId(),
                name,
                dataType,
                nextDisplayOrder,
                now);
        modelCustomFieldRepository.save(field);

        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "MODEL_CUSTOM_FIELD_CREATED",
                "MODEL_CUSTOM_FIELD",
                field.getId(),
                Map.of("assetModelId", assetModelId.toString(), "name", name, "dataType", dataType.name()));
        return ModelCustomFieldView.from(field);
    }

    @Transactional
    public ModelCustomFieldView rename(
            BigContainersPrincipal principal, UUID assetModelId, UUID fieldId, String newName) {
        requireOwnerOrDeputy(principal);
        ModelCustomField field = requireField(principal.organizationId(), assetModelId, fieldId);
        if (!field.getName().equalsIgnoreCase(newName)) {
            requireNameAvailable(assetModelId, newName);
        }
        field.rename(newName, clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "MODEL_CUSTOM_FIELD_RENAMED",
                "MODEL_CUSTOM_FIELD",
                field.getId(),
                Map.of("name", newName));
        return ModelCustomFieldView.from(field);
    }

    @Transactional
    public ModelCustomFieldView changeDataType(
            BigContainersPrincipal principal, UUID assetModelId, UUID fieldId, CustomFieldDataType newDataType) {
        requireOwnerOrDeputy(principal);
        ModelCustomField field = requireField(principal.organizationId(), assetModelId, fieldId);
        requireNoValueDependencyBlocksDataTypeChange(field);
        field.changeDataType(newDataType, clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "MODEL_CUSTOM_FIELD_DATA_TYPE_CHANGED",
                "MODEL_CUSTOM_FIELD",
                field.getId(),
                Map.of("dataType", newDataType.name()));
        return ModelCustomFieldView.from(field);
    }

    @Transactional
    public ModelCustomFieldView reorder(
            BigContainersPrincipal principal, UUID assetModelId, UUID fieldId, int newDisplayOrder) {
        requireOwnerOrDeputy(principal);
        ModelCustomField field = requireField(principal.organizationId(), assetModelId, fieldId);
        field.reorder(newDisplayOrder, clock.instant());
        return ModelCustomFieldView.from(field);
    }

    @Transactional
    public void archive(BigContainersPrincipal principal, UUID assetModelId, UUID fieldId) {
        requireOwnerOrDeputy(principal);
        ModelCustomField field = requireField(principal.organizationId(), assetModelId, fieldId);
        field.archive(clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "MODEL_CUSTOM_FIELD_ARCHIVED",
                "MODEL_CUSTOM_FIELD",
                field.getId(),
                null);
    }

    @Transactional
    public void restore(BigContainersPrincipal principal, UUID assetModelId, UUID fieldId) {
        requireOwnerOrDeputy(principal);
        ModelCustomField field = requireField(principal.organizationId(), assetModelId, fieldId);
        field.restore(clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "MODEL_CUSTOM_FIELD_RESTORED",
                "MODEL_CUSTOM_FIELD",
                field.getId(),
                null);
    }

    /**
     * Resolves an organization-scoped, non-archived {@link CustomFieldDataType#DROPDOWN} field for
     * {@code ModelCustomFieldOptionService} to attach an option to.
     */
    @Transactional(readOnly = true)
    ModelCustomField requireActiveDropdownFieldForOptionCreation(UUID organizationId, UUID assetModelId, UUID fieldId) {
        ModelCustomField field = requireField(organizationId, assetModelId, fieldId);
        if (!field.isDropdown()) {
            throw new ValidationFailedException("Options can only be added to a DROPDOWN field.");
        }
        if (field.isArchived()) {
            throw new ValidationFailedException("Cannot add an option to an archived field.");
        }
        return field;
    }

    @Transactional(readOnly = true)
    ModelCustomField requireFieldForOptionAccess(UUID organizationId, UUID assetModelId, UUID fieldId) {
        return requireField(organizationId, assetModelId, fieldId);
    }

    private ModelCustomField requireField(UUID organizationId, UUID assetModelId, UUID fieldId) {
        ModelCustomField field = modelCustomFieldRepository
                .findByIdAndOrganizationId(fieldId, organizationId)
                .orElseThrow(ModelCustomFieldService::fieldNotFound);
        if (!field.getAssetModelId().equals(assetModelId)) {
            throw fieldNotFound();
        }
        return field;
    }

    private void requireNoValueDependencyBlocksDataTypeChange(ModelCustomField field) {
        if (assetCustomFieldValueRepository.existsByModelCustomFieldId(field.getId())) {
            throw new ValidationFailedException("Cannot change data type while asset values exist for this field.");
        }
    }

    private void requireNameAvailable(UUID assetModelId, String name) {
        if (modelCustomFieldRepository
                .findByAssetModelIdAndNameIgnoreCaseAndArchivedAtIsNull(assetModelId, name == null ? "" : name.trim())
                .isPresent()) {
            throw new ValidationFailedException("A custom field with this name already exists on this model.");
        }
    }

    private static NotFoundException fieldNotFound() {
        return new NotFoundException("Model custom field not found.");
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

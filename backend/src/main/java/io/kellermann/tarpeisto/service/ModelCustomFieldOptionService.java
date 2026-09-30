package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.ModelCustomField;
import io.kellermann.tarpeisto.model.ModelCustomFieldOption;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.repository.ModelCustomFieldOptionRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dropdown option administration for a {@link ModelCustomField} (specification section 7.2:
 * "Dropdown options and their display order, when applicable"). Options may only be attached to a
 * {@code DROPDOWN} field - enforced by {@link
 * ModelCustomFieldService#requireActiveDropdownFieldForOptionCreation}.
 */
@Service
public class ModelCustomFieldOptionService {

    private final ModelCustomFieldOptionRepository optionRepository;
    private final ModelCustomFieldService modelCustomFieldService;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    public ModelCustomFieldOptionService(
            ModelCustomFieldOptionRepository optionRepository,
            ModelCustomFieldService modelCustomFieldService,
            ActivityLogService activityLogService,
            Clock clock) {
        this.optionRepository = optionRepository;
        this.modelCustomFieldService = modelCustomFieldService;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ModelCustomFieldOptionView> list(TarpeistoPrincipal principal, UUID assetModelId, UUID fieldId) {
        if (principal != null) principal.requirePermanent();
        requireAuthenticated(principal);
        modelCustomFieldService.requireFieldForOptionAccess(principal.organizationId(), assetModelId, fieldId);
        return optionRepository
                .findAllByOrganizationIdAndModelCustomFieldIdOrderByDisplayOrderAsc(principal.organizationId(), fieldId)
                .stream()
                .map(ModelCustomFieldOptionView::from)
                .toList();
    }

    @Transactional
    public ModelCustomFieldOptionView create(
            TarpeistoPrincipal principal, UUID assetModelId, UUID fieldId, String value) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        ModelCustomField field = modelCustomFieldService.requireActiveDropdownFieldForOptionCreation(
                principal.organizationId(), assetModelId, fieldId);
        requireValueAvailable(fieldId, value);

        int nextDisplayOrder = optionRepository
                        .findAllByOrganizationIdAndModelCustomFieldIdOrderByDisplayOrderAsc(
                                principal.organizationId(), fieldId)
                        .stream()
                        .mapToInt(ModelCustomFieldOption::getDisplayOrder)
                        .max()
                        .orElse(-1)
                + 1;

        var now = clock.instant();
        ModelCustomFieldOption option = new ModelCustomFieldOption(
                UUID.randomUUID(), principal.organizationId(), field.getId(), value, nextDisplayOrder, now);
        optionRepository.save(option);

        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "MODEL_CUSTOM_FIELD_OPTION_CREATED",
                "MODEL_CUSTOM_FIELD_OPTION",
                option.getId(),
                Map.of("fieldId", fieldId.toString(), "value", option.getValue()));
        return ModelCustomFieldOptionView.from(option);
    }

    @Transactional
    public ModelCustomFieldOptionView rename(
            TarpeistoPrincipal principal, UUID assetModelId, UUID fieldId, UUID optionId, String newValue) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        modelCustomFieldService.requireFieldForOptionAccess(principal.organizationId(), assetModelId, fieldId);
        ModelCustomFieldOption option = requireOption(principal.organizationId(), fieldId, optionId);
        if (!option.getValue().equalsIgnoreCase(newValue)) {
            requireValueAvailable(fieldId, newValue);
        }
        option.rename(newValue, clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "MODEL_CUSTOM_FIELD_OPTION_RENAMED",
                "MODEL_CUSTOM_FIELD_OPTION",
                option.getId(),
                Map.of("value", newValue));
        return ModelCustomFieldOptionView.from(option);
    }

    @Transactional
    public ModelCustomFieldOptionView reorder(
            TarpeistoPrincipal principal, UUID assetModelId, UUID fieldId, UUID optionId, int newDisplayOrder) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        modelCustomFieldService.requireFieldForOptionAccess(principal.organizationId(), assetModelId, fieldId);
        ModelCustomFieldOption option = requireOption(principal.organizationId(), fieldId, optionId);
        option.reorder(newDisplayOrder, clock.instant());
        return ModelCustomFieldOptionView.from(option);
    }

    @Transactional
    public void archive(TarpeistoPrincipal principal, UUID assetModelId, UUID fieldId, UUID optionId) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        modelCustomFieldService.requireFieldForOptionAccess(principal.organizationId(), assetModelId, fieldId);
        ModelCustomFieldOption option = requireOption(principal.organizationId(), fieldId, optionId);
        option.archive(clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "MODEL_CUSTOM_FIELD_OPTION_ARCHIVED",
                "MODEL_CUSTOM_FIELD_OPTION",
                option.getId(),
                null);
    }

    @Transactional
    public void restore(TarpeistoPrincipal principal, UUID assetModelId, UUID fieldId, UUID optionId) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        modelCustomFieldService.requireFieldForOptionAccess(principal.organizationId(), assetModelId, fieldId);
        ModelCustomFieldOption option = requireOption(principal.organizationId(), fieldId, optionId);
        option.restore(clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "MODEL_CUSTOM_FIELD_OPTION_RESTORED",
                "MODEL_CUSTOM_FIELD_OPTION",
                option.getId(),
                null);
    }

    private ModelCustomFieldOption requireOption(UUID organizationId, UUID fieldId, UUID optionId) {
        ModelCustomFieldOption option = optionRepository
                .findByIdAndOrganizationId(optionId, organizationId)
                .orElseThrow(ModelCustomFieldOptionService::optionNotFound);
        if (!option.getModelCustomFieldId().equals(fieldId)) {
            throw optionNotFound();
        }
        return option;
    }

    private void requireValueAvailable(UUID fieldId, String value) {
        if (optionRepository
                .findByModelCustomFieldIdAndValueIgnoreCaseAndArchivedAtIsNull(
                        fieldId, value == null ? "" : value.trim())
                .isPresent()) {
            throw new ValidationFailedException("An option with this value already exists on this field.");
        }
    }

    private static NotFoundException optionNotFound() {
        return new NotFoundException("Model custom field option not found.");
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

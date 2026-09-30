package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.InvalidAssetCodeException;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.PackingConflictException;
import io.kellermann.tarpeisto.exception.StalePackingRequirementVersionException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.AssetCode;
import io.kellermann.tarpeisto.model.AssetCodeValidation;
import io.kellermann.tarpeisto.model.AssetModel;
import io.kellermann.tarpeisto.model.ConsumableStock;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.PackingRequirement;
import io.kellermann.tarpeisto.model.PackingRequirementHistory;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import io.kellermann.tarpeisto.model.PackingTemplate;
import io.kellermann.tarpeisto.model.PackingTemplateRequirement;
import io.kellermann.tarpeisto.repository.AssetModelRepository;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.CheckoutManifestAssetRepository;
import io.kellermann.tarpeisto.repository.ConsumableStockRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.repository.PackingRequirementHistoryRepository;
import io.kellermann.tarpeisto.repository.PackingRequirementRepository;
import io.kellermann.tarpeisto.repository.PackingTemplateRepository;
import io.kellermann.tarpeisto.repository.PackingTemplateRequirementRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 5 packing administration. Template application always creates independent requirement rows.
 *
 * <p>All mutations share the organization lock and transaction. Future checkout guards must run
 * before mutation, and future seal invalidation must participate in this same transaction once
 * those lifecycles have real persisted state; Phase 5 has neither lifecycle yet.
 */
@Service
public class PackingRequirementService {
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("100000000000");
    private static final BigDecimal MAX_SERIALIZED_QUANTITY = BigDecimal.valueOf(Integer.MAX_VALUE);
    private final PackingRequirementRepository requirements;
    private final PackingTemplateRepository templates;
    private final PackingTemplateRequirementRepository templateRequirements;
    private final AssetRepository assets;
    private final AssetModelRepository models;
    private final ConsumableStockRepository stockBalances;
    private final PackingRequirementHistoryRepository histories;
    private final OrganizationRepository organizations;
    private final ActivityLogService activity;
    private final BookingImpactService bookingImpact;
    private final CheckoutManifestAssetRepository checkoutManifestAssets;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AssetSealService seals;
    private final AssetPlacementService placementService;

    public PackingRequirementService(
            PackingRequirementRepository requirements,
            PackingTemplateRepository templates,
            PackingTemplateRequirementRepository templateRequirements,
            AssetRepository assets,
            AssetModelRepository models,
            ConsumableStockRepository stockBalances,
            PackingRequirementHistoryRepository histories,
            OrganizationRepository organizations,
            ActivityLogService activity,
            BookingImpactService bookingImpact,
            CheckoutManifestAssetRepository checkoutManifestAssets,
            AssetSealService seals,
            AssetPlacementService placementService,
            Clock clock,
            ObjectMapper objectMapper) {
        this.requirements = requirements;
        this.templates = templates;
        this.templateRequirements = templateRequirements;
        this.assets = assets;
        this.models = models;
        this.stockBalances = stockBalances;
        this.histories = histories;
        this.organizations = organizations;
        this.activity = activity;
        this.bookingImpact = bookingImpact;
        this.checkoutManifestAssets = checkoutManifestAssets;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.seals = seals;
        this.placementService = placementService;
    }

    @Transactional(readOnly = true)
    public List<PackingRequirementView> list(TarpeistoPrincipal p, UUID container) {
        if (p != null) p.requirePermanent();
        auth(p);
        requireContainerForRead(p.organizationId(), container);
        return requirements
                .findAllByOrganizationIdAndContainerAssetIdOrderByDisplayOrderAsc(p.organizationId(), container)
                .stream()
                .map(this::view)
                .toList();
    }

    private UUID resolveAssetReference(UUID organizationId, String reference) {
        if (reference == null || reference.isBlank()) return null;
        UUID assetId;
        try {
            assetId = UUID.fromString(reference.trim());
        } catch (IllegalArgumentException notUuid) {
            AssetCodeValidation validation = AssetCode.validate(reference);
            if (validation instanceof AssetCodeValidation.Invalid invalid)
                throw new InvalidAssetCodeException(invalid.reason());
            String code = ((AssetCodeValidation.Valid) validation).code().value();
            return assets.findByOrganizationIdAndPublicCode(organizationId, code)
                    .map(Asset::getId)
                    .orElseThrow(() -> new NotFoundException("Asset not found."));
        }
        return assets.findByIdAndOrganizationId(assetId, organizationId)
                .map(Asset::getId)
                .orElseThrow(() -> new NotFoundException("Asset not found."));
    }

    /**
     * Evaluates only direct serialized contents. Exact requirements reserve their named asset
     * first; any asset pinned anywhere else in the organization is not eligible for an
     * interchangeable-model requirement.
     */
    @Transactional(readOnly = true)
    public PackingPreviewView preview(TarpeistoPrincipal p, UUID container, Map<UUID, BigDecimal> observations) {
        if (p != null) p.requirePermanent();
        auth(p);
        requireContainerForRead(p.organizationId(), container);
        List<PackingRequirement> rows = requirements
                .findAllByOrganizationIdAndContainerAssetIdOrderByDisplayOrderAsc(p.organizationId(), container)
                .stream()
                .filter(row -> !row.isArchived())
                .toList();
        if (observations != null) {
            Set<UUID> consumableIds = new HashSet<>();
            for (PackingRequirement row : rows)
                if (row.getRequirementType() == PackingRequirementType.CONSUMABLE_QUANTITY)
                    consumableIds.add(row.getId());
            for (Map.Entry<UUID, BigDecimal> observation : observations.entrySet()) {
                if (!consumableIds.contains(observation.getKey()))
                    throw new ValidationFailedException(
                            "Observation must reference this container's active consumable requirement.");
                validateQuantity(observation.getValue(), false);
            }
        }
        List<Asset> contents = assets.findAllByOrganizationIdAndParentContainerAssetIdOrderByUnitNumberAsc(
                p.organizationId(), container);
        Set<UUID> usedAssetIds = new HashSet<>();
        List<UUID> satisfied = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        List<PackingPreviewView.ConsumableRequirementStatus> consumables = new ArrayList<>();

        for (PackingRequirement row : rows) {
            if (row.getRequirementType() != PackingRequirementType.SPECIFIC_ASSET) {
                continue;
            }
            if (contents.stream()
                    .anyMatch(asset -> asset.getId().equals(row.getSpecificAssetId()) && asset.isActive())) {
                usedAssetIds.add(row.getSpecificAssetId());
                satisfied.add(row.getId());
            } else {
                missing.add(row.getId());
            }
        }
        for (PackingRequirement row : rows) {
            if (row.getRequirementType() != PackingRequirementType.MODEL_QUANTITY) {
                continue;
            }
            int remaining = row.getRequiredQuantity().intValueExact();
            for (Asset candidate : contents) {
                if (remaining == 0) {
                    break;
                }
                if (!candidate.isActive()
                        || usedAssetIds.contains(candidate.getId())
                        || !candidate.getAssetModelId().equals(row.getAssetModelId())
                        || requirements.existsByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(
                                p.organizationId(), candidate.getId())) {
                    continue;
                }
                usedAssetIds.add(candidate.getId());
                remaining--;
            }
            if (remaining == 0) {
                satisfied.add(row.getId());
            } else {
                missing.add(row.getId());
            }
        }
        for (PackingRequirement row : rows) {
            if (row.getRequirementType() != PackingRequirementType.CONSUMABLE_QUANTITY) {
                continue;
            }
            BigDecimal observed = observations != null && observations.get(row.getId()) != null
                    ? observations.get(row.getId())
                    : stockBalances
                            .findByOrganizationIdAndAssetModelIdAndContainerAssetId(
                                    p.organizationId(), row.getAssetModelId(), container)
                            .map(ConsumableStock::getQuantity)
                            .orElse(BigDecimal.ZERO);
            boolean supplied = observed.compareTo(row.getRequiredQuantity()) >= 0;
            consumables.add(new PackingPreviewView.ConsumableRequirementStatus(
                    row.getId(), row.getAssetModelId(), row.getRequiredQuantity(), observed, supplied));
            if (supplied) {
                satisfied.add(row.getId());
            } else {
                missing.add(row.getId());
            }
        }
        List<UUID> misplaced = contents.stream()
                .filter(Asset::isActive)
                .map(Asset::getId)
                .filter(id -> !usedAssetIds.contains(id))
                .filter(id -> requirements.existsByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(
                        p.organizationId(), id))
                .toList();
        List<UUID> extras = contents.stream()
                .filter(Asset::isActive)
                .map(Asset::getId)
                .filter(id -> !usedAssetIds.contains(id) && !misplaced.contains(id))
                .toList();
        return new PackingPreviewView(
                missing.isEmpty() && misplaced.isEmpty() && extras.isEmpty(),
                satisfied,
                missing,
                extras,
                misplaced,
                consumables);
    }

    @Transactional
    public PackingRequirementView add(
            TarpeistoPrincipal p,
            UUID container,
            PackingRequirementType type,
            UUID model,
            String assetReference,
            BigDecimal quantity) {
        if (p != null) p.requirePermanent();
        return add(p, container, type, model, null, assetReference, quantity, false, null);
    }

    @Transactional
    public PackingRequirementView add(
            TarpeistoPrincipal p,
            UUID container,
            PackingRequirementType type,
            UUID model,
            UUID specificAssetId,
            String assetReference,
            BigDecimal quantity,
            boolean assignToContainer,
            Long expectedAssetVersion) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        requireContainerForWrite(p.organizationId(), container);
        if (specificAssetId != null && assetReference != null && !assetReference.isBlank()) {
            throw new ValidationFailedException("Select an exact asset by id or reference, not both.");
        }
        UUID asset = specificAssetId == null
                ? resolveAssetReference(p.organizationId(), assetReference)
                : resolveAssetReference(p.organizationId(), specificAssetId.toString());
        if (assignToContainer && type != PackingRequirementType.SPECIFIC_ASSET) {
            throw new ValidationFailedException(
                    "Only an exact asset requirement can assign an asset to the container.");
        }
        if (assignToContainer && expectedAssetVersion == null) {
            throw new ValidationFailedException("expectedAssetVersion is required when assigning an exact asset.");
        }
        validate(p.organizationId(), type, model, asset, quantity);
        requireAvailable(p.organizationId(), container, null, type, model, asset);
        int order = requirements
                .findAllByOrganizationIdAndContainerAssetIdOrderByDisplayOrderAsc(p.organizationId(), container)
                .size();
        PackingRequirement row = new PackingRequirement(
                UUID.randomUUID(), p.organizationId(), container, type, model, asset, quantity, order, clock.instant());
        requirements.save(row);
        history(p, row, "CREATED", null, snapshot(row));
        activity.record(
                p.organizationId(),
                p.userId(),
                "PACKING_REQUIREMENT_CREATED",
                "PACKING_REQUIREMENT",
                row.getId(),
                transition(null, snapshot(row)));
        requirements.flush();
        if (assignToContainer) {
            placementService.move(p, asset, null, container, expectedAssetVersion);
        }
        bookingImpact.changed(p);
        return view(row);
    }

    @Transactional
    public void archive(TarpeistoPrincipal p, UUID id, long expectedVersion) {
        if (p != null) p.requirePermanent();
        archive(p, id, expectedVersion, false);
    }

    @Transactional(readOnly = true)
    public List<UUID> reservationImpact(TarpeistoPrincipal p, UUID id) {
        if (p != null) p.requirePermanent();
        auth(p);
        PackingRequirement row = requirements
                .findByIdAndOrganizationId(id, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing requirement not found."));
        return bookingImpact.affectedBookings(p.organizationId(), row.getContainerAssetId());
    }

    @Transactional
    public void archive(TarpeistoPrincipal p, UUID id, long expectedVersion, boolean confirmed) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        PackingRequirement row = requirements
                .findByIdAndOrganizationId(id, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing requirement not found."));
        if (row.getVersion() != expectedVersion) throw new StalePackingRequirementVersionException();
        requireContainerForWrite(p.organizationId(), row.getContainerAssetId());
        if (row.isArchived()) return;
        List<UUID> affected = bookingImpact.affectedBookings(p.organizationId(), row.getContainerAssetId());
        if (!affected.isEmpty() && !confirmed)
            throw new io.kellermann.tarpeisto.exception.PackingRemovalConfirmationException(affected);
        Map<String, Object> before = snapshot(row);
        row.archive(clock.instant());
        history(p, row, "ARCHIVED", before, snapshot(row));
        activity.record(
                p.organizationId(),
                p.userId(),
                "PACKING_REQUIREMENT_ARCHIVED",
                "PACKING_REQUIREMENT",
                id,
                transition(before, snapshot(row)));
        bookingImpact.changed(p);
    }

    @Transactional
    public PackingRequirementView update(
            TarpeistoPrincipal p,
            UUID id,
            long expectedVersion,
            PackingRequirementType type,
            UUID model,
            String assetReference,
            BigDecimal quantity) {
        if (p != null) p.requirePermanent();
        return update(p, id, expectedVersion, type, model, assetReference, quantity, false);
    }

    @Transactional
    public PackingRequirementView update(
            TarpeistoPrincipal p,
            UUID id,
            long expectedVersion,
            PackingRequirementType type,
            UUID model,
            String assetReference,
            BigDecimal quantity,
            boolean confirmed) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        PackingRequirement row = requirements
                .findByIdAndOrganizationId(id, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing requirement not found."));
        if (row.getVersion() != expectedVersion) throw new StalePackingRequirementVersionException();
        requireContainerForWrite(p.organizationId(), row.getContainerAssetId());
        UUID asset = resolveAssetReference(p.organizationId(), assetReference);
        validate(p.organizationId(), type, model, asset, quantity);
        if (row.isArchived()) throw new ValidationFailedException("Restore a requirement before editing it.");
        requireAvailable(p.organizationId(), row.getContainerAssetId(), id, type, model, asset);
        boolean removes = row.getRequirementType() != type
                || !java.util.Objects.equals(row.getAssetModelId(), model)
                || !java.util.Objects.equals(row.getSpecificAssetId(), asset)
                || quantity.compareTo(row.getRequiredQuantity()) < 0;
        if (removes && !confirmed) {
            List<UUID> affected = bookingImpact.affectedBookings(p.organizationId(), row.getContainerAssetId());
            if (!affected.isEmpty())
                throw new io.kellermann.tarpeisto.exception.PackingRemovalConfirmationException(affected);
        }
        Map<String, Object> before = snapshot(row);
        row.change(type, model, asset, quantity, clock.instant());
        history(p, row, "UPDATED", before, snapshot(row));
        requirements.flush();
        activity.record(
                p.organizationId(),
                p.userId(),
                "PACKING_REQUIREMENT_UPDATED",
                "PACKING_REQUIREMENT",
                id,
                transition(before, snapshot(row)));
        bookingImpact.changed(p);
        return view(row);
    }

    @Transactional
    public PackingRequirementView restore(TarpeistoPrincipal p, UUID id, long expectedVersion) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        PackingRequirement row = requirements
                .findByIdAndOrganizationId(id, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing requirement not found."));
        if (row.getVersion() != expectedVersion) throw new StalePackingRequirementVersionException();
        requireContainerForWrite(p.organizationId(), row.getContainerAssetId());
        validate(
                p.organizationId(),
                row.getRequirementType(),
                row.getAssetModelId(),
                row.getSpecificAssetId(),
                row.getRequiredQuantity());
        requireAvailable(
                p.organizationId(),
                row.getContainerAssetId(),
                id,
                row.getRequirementType(),
                row.getAssetModelId(),
                row.getSpecificAssetId());
        if (!row.isArchived()) return view(row);
        Map<String, Object> before = snapshot(row);
        row.restore(clock.instant());
        history(p, row, "RESTORED", before, snapshot(row));
        requirements.flush();
        activity.record(
                p.organizationId(),
                p.userId(),
                "PACKING_REQUIREMENT_RESTORED",
                "PACKING_REQUIREMENT",
                id,
                transition(before, snapshot(row)));
        bookingImpact.changed(p);
        return view(row);
    }

    @Transactional(readOnly = true)
    public List<PackingTemplateView> listTemplates(TarpeistoPrincipal p) {
        if (p != null) p.requirePermanent();
        auth(p);
        return templates.findAllByOrganizationIdOrderByNameAsc(p.organizationId()).stream()
                .map(t -> templateView(p.organizationId(), t))
                .toList();
    }

    @Transactional
    public PackingTemplateView createTemplate(TarpeistoPrincipal p, String name, String description) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        requireTemplateNameAvailable(p.organizationId(), name, null);
        PackingTemplate t;
        try {
            t = new PackingTemplate(UUID.randomUUID(), p.organizationId(), name, description, clock.instant());
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException(e.getMessage());
        }
        templates.save(t);
        activity.record(
                p.organizationId(),
                p.userId(),
                "PACKING_TEMPLATE_CREATED",
                "PACKING_TEMPLATE",
                t.getId(),
                transition(null, snapshot(t)));
        return templateView(p.organizationId(), t);
    }

    @Transactional
    public PackingTemplateView updateTemplate(
            TarpeistoPrincipal p, UUID id, long expectedVersion, String name, String description) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        PackingTemplate template = templates
                .findByIdAndOrganizationId(id, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing template not found."));
        if (template.getVersion() != expectedVersion) throw new StalePackingRequirementVersionException();
        if (template.isArchived()) throw new ValidationFailedException("Restore a template before editing it.");
        requireTemplateNameAvailable(p.organizationId(), name, id);
        Map<String, Object> before = snapshot(template);
        try {
            template.rename(name, description, clock.instant());
        } catch (IllegalArgumentException exception) {
            throw new ValidationFailedException(exception.getMessage());
        }
        templates.flush();
        activity.record(
                p.organizationId(),
                p.userId(),
                "PACKING_TEMPLATE_UPDATED",
                "PACKING_TEMPLATE",
                id,
                transition(before, snapshot(template)));
        return templateView(p.organizationId(), template);
    }

    @Transactional
    public PackingTemplateView setTemplateArchived(
            TarpeistoPrincipal p, UUID id, long expectedVersion, boolean archived) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        PackingTemplate template = templates
                .findByIdAndOrganizationId(id, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing template not found."));
        if (template.getVersion() != expectedVersion) throw new StalePackingRequirementVersionException();
        if (!archived) requireTemplateNameAvailable(p.organizationId(), template.getName(), id);
        Map<String, Object> before = snapshot(template);
        if (archived) template.archive(clock.instant());
        else template.restore(clock.instant());
        templates.flush();
        activity.record(
                p.organizationId(),
                p.userId(),
                archived ? "PACKING_TEMPLATE_ARCHIVED" : "PACKING_TEMPLATE_RESTORED",
                "PACKING_TEMPLATE",
                id,
                transition(before, snapshot(template)));
        return templateView(p.organizationId(), template);
    }

    @Transactional
    public PackingTemplateView addTemplateRequirement(
            TarpeistoPrincipal p,
            UUID templateId,
            PackingRequirementType type,
            UUID model,
            String assetReference,
            BigDecimal quantity) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        PackingTemplate template = templates
                .findByIdAndOrganizationId(templateId, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing template not found."));
        if (template.isArchived()) throw new ValidationFailedException("An archived template cannot be changed.");
        UUID asset = resolveAssetReference(p.organizationId(), assetReference);
        validate(p.organizationId(), type, model, asset, quantity);
        int order = templateRequirements
                .findAllByOrganizationIdAndPackingTemplateIdOrderByDisplayOrderAsc(p.organizationId(), templateId)
                .size();
        requireTemplateAvailable(p.organizationId(), templateId, null, type, model, asset);
        PackingTemplateRequirement row = new PackingTemplateRequirement(
                UUID.randomUUID(),
                p.organizationId(),
                templateId,
                type,
                model,
                asset,
                quantity,
                order,
                clock.instant());
        templateRequirements.save(row);
        templateHistory(p, row, "CREATED", null, snapshot(row));
        activity.record(
                p.organizationId(),
                p.userId(),
                "PACKING_TEMPLATE_REQUIREMENT_CREATED",
                "PACKING_TEMPLATE",
                templateId,
                transition(null, snapshot(row)));
        return templateView(p.organizationId(), template);
    }

    @Transactional
    public PackingTemplateView updateTemplateRequirement(
            TarpeistoPrincipal p,
            UUID id,
            long expectedVersion,
            PackingRequirementType type,
            UUID model,
            String assetReference,
            BigDecimal quantity) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        PackingTemplateRequirement row = templateRequirements
                .findByIdAndOrganizationId(id, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing template requirement not found."));
        if (row.getVersion() != expectedVersion) throw new StalePackingRequirementVersionException();
        PackingTemplate template = templates
                .findByIdAndOrganizationId(row.getPackingTemplateId(), p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing template not found."));
        if (template.isArchived()) throw new ValidationFailedException("An archived template cannot be changed.");
        UUID asset = resolveAssetReference(p.organizationId(), assetReference);
        validate(p.organizationId(), type, model, asset, quantity);
        if (row.isArchived()) throw new ValidationFailedException("Restore a requirement before editing it.");
        requireTemplateAvailable(p.organizationId(), template.getId(), id, type, model, asset);
        Map<String, Object> before = snapshot(row);
        row.change(type, model, asset, quantity, clock.instant());
        templateHistory(p, row, "UPDATED", before, snapshot(row));
        templateRequirements.flush();
        activity.record(
                p.organizationId(),
                p.userId(),
                "PACKING_TEMPLATE_REQUIREMENT_UPDATED",
                "PACKING_TEMPLATE",
                template.getId(),
                transition(before, snapshot(row)));
        return templateView(p.organizationId(), template);
    }

    @Transactional
    public PackingTemplateView setTemplateRequirementArchived(
            TarpeistoPrincipal p, UUID id, long expectedVersion, boolean archived) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        PackingTemplateRequirement row = templateRequirements
                .findByIdAndOrganizationId(id, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing template requirement not found."));
        if (row.getVersion() != expectedVersion) throw new StalePackingRequirementVersionException();
        PackingTemplate template = templates
                .findByIdAndOrganizationId(row.getPackingTemplateId(), p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing template not found."));
        if (template.isArchived()) throw new ValidationFailedException("An archived template cannot be changed.");
        if (!archived) {
            validate(
                    p.organizationId(),
                    row.getRequirementType(),
                    row.getAssetModelId(),
                    row.getSpecificAssetId(),
                    row.getRequiredQuantity());
            requireTemplateAvailable(
                    p.organizationId(),
                    template.getId(),
                    id,
                    row.getRequirementType(),
                    row.getAssetModelId(),
                    row.getSpecificAssetId());
        }
        Map<String, Object> before = snapshot(row);
        if (archived) row.archive(clock.instant());
        else row.restore(clock.instant());
        templateHistory(p, row, archived ? "ARCHIVED" : "RESTORED", before, snapshot(row));
        templateRequirements.flush();
        activity.record(
                p.organizationId(),
                p.userId(),
                archived ? "PACKING_TEMPLATE_REQUIREMENT_ARCHIVED" : "PACKING_TEMPLATE_REQUIREMENT_RESTORED",
                "PACKING_TEMPLATE",
                template.getId(),
                transition(before, snapshot(row)));
        return templateView(p.organizationId(), template);
    }

    @Transactional
    public List<PackingRequirementView> applyTemplate(TarpeistoPrincipal p, UUID container, UUID templateId) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        requireContainerForWrite(p.organizationId(), container);
        PackingTemplate t = templates
                .findByIdAndOrganizationId(templateId, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Packing template not found."));
        if (t.isArchived()) throw new ValidationFailedException("An archived template cannot be applied.");
        List<PackingRequirementView> added = new ArrayList<>();
        for (PackingTemplateRequirement source :
                templateRequirements.findAllByOrganizationIdAndPackingTemplateIdOrderByDisplayOrderAsc(
                        p.organizationId(), templateId)) {
            if (!source.isArchived())
                added.add(add(
                        p,
                        container,
                        source.getRequirementType(),
                        source.getAssetModelId(),
                        source.getSpecificAssetId() == null
                                ? null
                                : source.getSpecificAssetId().toString(),
                        source.getRequiredQuantity()));
        }
        activity.record(
                p.organizationId(),
                p.userId(),
                "PACKING_TEMPLATE_APPLIED",
                "ASSET",
                container,
                Map.of(
                        "templateId",
                        templateId.toString(),
                        "templateVersion",
                        t.getVersion(),
                        "addedRequirementIds",
                        added.stream().map(PackingRequirementView::id).toList()));
        return added;
    }

    private void validate(UUID org, PackingRequirementType type, UUID modelId, UUID assetId, BigDecimal quantity) {
        if (type == null) throw new ValidationFailedException("A requirement kind is required.");
        validateQuantity(quantity, true);
        if (type == PackingRequirementType.SPECIFIC_ASSET) {
            if (assetId == null || modelId != null || quantity.compareTo(BigDecimal.ONE) != 0)
                throw new ValidationFailedException(
                        "An exact asset requirement must select one asset with quantity 1.");
            Asset a = assets.findByIdAndOrganizationId(assetId, org)
                    .orElseThrow(() -> new NotFoundException("Asset not found."));
            AssetModel m = models.findByIdAndOrganizationId(a.getAssetModelId(), org)
                    .orElseThrow(() -> new NotFoundException("Asset not found."));
            if (!a.isActive() || m.isArchived())
                throw new ValidationFailedException("An exact requirement must reference an active asset and model.");
            if (m.isQuantityTracked())
                throw new ValidationFailedException("An exact requirement must reference a serialized asset.");
            return;
        }
        if (modelId == null || assetId != null)
            throw new ValidationFailedException("A model requirement must select a model.");
        AssetModel m = models.findByIdAndOrganizationId(modelId, org)
                .orElseThrow(() -> new NotFoundException("Asset model not found."));
        if (m.isArchived()) throw new ValidationFailedException("An archived model cannot be selected.");
        if (type == PackingRequirementType.MODEL_QUANTITY
                && (m.isQuantityTracked()
                        || quantity.stripTrailingZeros().scale() > 0
                        || quantity.compareTo(MAX_SERIALIZED_QUANTITY) > 0))
            throw new ValidationFailedException(
                    "An interchangeable requirement requires a positive whole serialized quantity.");
        if (type == PackingRequirementType.CONSUMABLE_QUANTITY && !m.isQuantityTracked())
            throw new ValidationFailedException("A consumable requirement requires a quantity-stock model.");
    }

    private Asset requireContainerForRead(UUID org, UUID id) {
        Asset a = assets.findByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new NotFoundException("Container asset not found."));
        AssetModel m = models.findByIdAndOrganizationId(a.getAssetModelId(), org)
                .orElseThrow(() -> new NotFoundException("Container asset not found."));
        if (!m.isCanContainAssets())
            throw new ValidationFailedException("Only a container-capable asset can own packing requirements.");
        return a;
    }

    private Asset requireContainerForWrite(UUID org, UUID id) {
        Asset a = assets.findWithLockByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new NotFoundException("Container asset not found."));
        AssetModel m = models.findByIdAndOrganizationId(a.getAssetModelId(), org)
                .orElseThrow(() -> new NotFoundException("Container asset not found."));
        if (!m.isCanContainAssets()) {
            throw new ValidationFailedException("Only a container-capable asset can own packing requirements.");
        }
        if (!a.isActive()) {
            throw new ValidationFailedException("Packing requirements can only be managed on an active container.");
        }
        requireNoActiveCustody(org, a);
        if (a.getIndividualName() == null || a.getIndividualName().isBlank()) {
            throw new ValidationFailedException(
                    "A container needs an individual name before packing requirements can be managed.");
        }
        return a;
    }

    /** A container is frozen while it, or any enclosing container, remains in event custody. */
    private void requireNoActiveCustody(UUID organizationId, Asset container) {
        Set<UUID> visited = new HashSet<>();
        for (Asset current = container; current != null; ) {
            if (!visited.add(current.getId())) {
                throw new ValidationFailedException("Containment hierarchy is invalid.");
            }
            if (checkoutManifestAssets.existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                    organizationId, current.getId())) {
                throw new ValidationFailedException(
                        "Packing requirements cannot change while this container is checked out or awaiting audit.");
            }
            UUID parentId = current.getParentContainerAssetId();
            current = parentId == null
                    ? null
                    : assets.findByIdAndOrganizationId(parentId, organizationId)
                            .orElseThrow(() -> new ValidationFailedException("Containment hierarchy is invalid."));
        }
    }

    private void validateQuantity(BigDecimal quantity, boolean positive) {
        if (quantity == null
                || quantity.signum() < 0
                || (positive && quantity.signum() == 0)
                || quantity.scale() > 3
                || quantity.compareTo(MAX_QUANTITY) >= 0)
            throw new ValidationFailedException("Quantity must be " + (positive ? "positive" : "non-negative")
                    + ", below 100000000000, with at most three decimals.");
    }

    private void requireAvailable(
            UUID org, UUID container, UUID exceptId, PackingRequirementType type, UUID model, UUID asset) {
        if (container.equals(asset)) throw new ValidationFailedException("A container cannot require itself.");
        if (type == PackingRequirementType.SPECIFIC_ASSET) {
            PackingRequirement pin = requirements
                    .findByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(org, asset)
                    .orElse(null);
            if (pin != null && !pin.getId().equals(exceptId))
                throw new PackingConflictException("This asset is already pinned by another active requirement.");
        } else {
            for (PackingRequirement row :
                    requirements.findAllByOrganizationIdAndContainerAssetIdOrderByDisplayOrderAsc(org, container))
                if (!row.isArchived()
                        && !row.getId().equals(exceptId)
                        && row.getRequirementType() == type
                        && model.equals(row.getAssetModelId()))
                    throw new PackingConflictException(
                            "Edit the existing requirement for this model instead of adding a duplicate.");
        }
    }

    private void requireTemplateAvailable(
            UUID org, UUID template, UUID exceptId, PackingRequirementType type, UUID model, UUID asset) {
        for (PackingTemplateRequirement row :
                templateRequirements.findAllByOrganizationIdAndPackingTemplateIdOrderByDisplayOrderAsc(org, template))
            if (!row.isArchived()
                    && !row.getId().equals(exceptId)
                    && row.getRequirementType() == type
                    && (type == PackingRequirementType.SPECIFIC_ASSET
                            ? asset.equals(row.getSpecificAssetId())
                            : model.equals(row.getAssetModelId())))
                throw new PackingConflictException("The template already has this requirement.");
    }

    private void requireTemplateNameAvailable(UUID org, String name, UUID exceptId) {
        if (name == null || name.isBlank() || name.trim().length() > 160)
            throw new ValidationFailedException("A template name of at most 160 characters is required.");
        for (PackingTemplate template : templates.findAllByOrganizationIdOrderByNameAsc(org))
            if (!template.isArchived()
                    && !template.getId().equals(exceptId)
                    && template.getName().equalsIgnoreCase(name.trim()))
                throw new PackingConflictException("An active template already has this name.");
    }

    private Map<String, Object> snapshot(PackingRequirement row) {
        Map<String, Object> values = requirementSnapshot(
                row.getId(),
                row.getRequirementType(),
                row.getAssetModelId(),
                row.getSpecificAssetId(),
                row.getRequiredQuantity(),
                row.getDisplayOrder(),
                row.isArchived(),
                row.getOrganizationId());
        values.put("containerAssetId", row.getContainerAssetId());
        return values;
    }

    private Map<String, Object> snapshot(PackingTemplateRequirement row) {
        Map<String, Object> values = requirementSnapshot(
                row.getId(),
                row.getRequirementType(),
                row.getAssetModelId(),
                row.getSpecificAssetId(),
                row.getRequiredQuantity(),
                row.getDisplayOrder(),
                row.isArchived(),
                row.getOrganizationId());
        values.put("templateId", row.getPackingTemplateId());
        return values;
    }

    private Map<String, Object> requirementSnapshot(
            UUID id,
            PackingRequirementType type,
            UUID modelId,
            UUID assetId,
            BigDecimal quantity,
            int order,
            boolean archived,
            UUID org) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("type", type.name());
        values.put("assetModelId", modelId);
        values.put("specificAssetId", assetId);
        values.put("quantity", quantity);
        values.put("displayOrder", order);
        values.put("archived", archived);
        UUID namedModelId = modelId;
        if (assetId != null) {
            Asset target = assets.findByIdAndOrganizationId(assetId, org)
                    .orElseThrow(() -> new NotFoundException("Asset not found."));
            values.put("assetCode", target.getPublicCode());
            values.put("individualName", target.getIndividualName());
            namedModelId = target.getAssetModelId();
        }
        if (namedModelId != null) {
            AssetModel model = models.findByIdAndOrganizationId(namedModelId, org)
                    .orElseThrow(() -> new NotFoundException("Asset model not found."));
            values.put("modelName", model.getName());
            values.put("stockUnitLabel", model.getStockUnitLabel());
        }
        return values;
    }

    private Map<String, Object> snapshot(PackingTemplate template) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", template.getId());
        values.put("name", template.getName());
        values.put("description", template.getDescription());
        values.put("archived", template.isArchived());
        return values;
    }

    private Map<String, Object> transition(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", before);
        change.put("after", after);
        return change;
    }

    private void history(
            TarpeistoPrincipal principal,
            PackingRequirement row,
            String action,
            Map<String, Object> before,
            Map<String, Object> after) {
        seals.invalidate(principal, row.getContainerAssetId(), "Packing requirements changed.");
        histories.save(new PackingRequirementHistory(
                UUID.randomUUID(),
                principal.organizationId(),
                row.getId(),
                null,
                action,
                principal.userId(),
                clock.instant(),
                objectMapper.writeValueAsString(transition(before, after))));
    }

    private void templateHistory(
            TarpeistoPrincipal principal,
            PackingTemplateRequirement row,
            String action,
            Map<String, Object> before,
            Map<String, Object> after) {
        histories.save(new PackingRequirementHistory(
                UUID.randomUUID(),
                principal.organizationId(),
                null,
                row.getId(),
                action,
                principal.userId(),
                clock.instant(),
                objectMapper.writeValueAsString(transition(before, after))));
    }

    private PackingRequirementView view(PackingRequirement r) {
        return new PackingRequirementView(
                r.getId(),
                r.getRequirementType(),
                r.getAssetModelId(),
                r.getSpecificAssetId(),
                r.getRequiredQuantity(),
                r.getDisplayOrder(),
                r.isArchived(),
                r.getVersion());
    }

    private PackingTemplateView templateView(UUID org, PackingTemplate t) {
        return new PackingTemplateView(
                t.getId(),
                t.getName(),
                t.getDescription(),
                t.isArchived(),
                t.getVersion(),
                templateRequirements
                        .findAllByOrganizationIdAndPackingTemplateIdOrderByDisplayOrderAsc(org, t.getId())
                        .stream()
                        .map(r -> new PackingRequirementView(
                                r.getId(),
                                r.getRequirementType(),
                                r.getAssetModelId(),
                                r.getSpecificAssetId(),
                                r.getRequiredQuantity(),
                                r.getDisplayOrder(),
                                r.isArchived(),
                                r.getVersion()))
                        .toList());
    }

    private void lock(UUID org) {
        organizations.findWithLockById(org).orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private static void auth(TarpeistoPrincipal p) {
        if (p == null) throw new AccessDeniedException("Authentication required.");
    }

    private static void admin(TarpeistoPrincipal p) {
        auth(p);
        if (p.role() != OrganizationRole.OWNER && p.role() != OrganizationRole.DEPUTY)
            throw new AccessDeniedException("Owner or Deputy role required.");
    }
}

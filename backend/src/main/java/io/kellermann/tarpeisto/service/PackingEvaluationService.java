package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.AssetModel;
import io.kellermann.tarpeisto.model.ConsumableStock;
import io.kellermann.tarpeisto.model.PackingRequirement;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import io.kellermann.tarpeisto.repository.AssetModelRepository;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.ConsumableStockRepository;
import io.kellermann.tarpeisto.repository.PackingRequirementRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared current packing matcher; observation overrides retain the public preview semantics. */
@Service
public class PackingEvaluationService {
    private final PackingRequirementRepository requirements;
    private final AssetRepository assets;
    private final AssetModelRepository models;
    private final ConsumableStockRepository stockBalances;

    public PackingEvaluationService(
            PackingRequirementRepository requirements,
            AssetRepository assets,
            AssetModelRepository models,
            ConsumableStockRepository stockBalances) {
        this.requirements = requirements;
        this.assets = assets;
        this.models = models;
        this.stockBalances = stockBalances;
    }

    @Transactional(readOnly = true)
    public Evaluation evaluate(TarpeistoPrincipal p, UUID container, Map<UUID, BigDecimal> observations) {
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
        List<UUID> contentIds = contents.stream().map(Asset::getId).toList();
        Set<UUID> pinnedAssetIds = contentIds.isEmpty()
                ? Set.of()
                : Set.copyOf(requirements.findPinnedAssetIds(p.organizationId(), contentIds));
        List<ConsumableStock> balances = stockBalances.findAllByOrganizationIdAndContainerAssetIdOrderByCreatedAtAsc(
                p.organizationId(), container);
        Map<UUID, BigDecimal> stockByModel = balances.stream()
                .collect(Collectors.toMap(ConsumableStock::getAssetModelId, ConsumableStock::getQuantity));
        Map<UUID, List<UUID>> matchedByRequirement = new LinkedHashMap<>();
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
                matchedByRequirement.put(row.getId(), List.of(row.getSpecificAssetId()));
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
            List<UUID> matched = new ArrayList<>();
            for (Asset candidate : contents) {
                if (remaining == 0) {
                    break;
                }
                if (!candidate.isActive()
                        || usedAssetIds.contains(candidate.getId())
                        || !candidate.getAssetModelId().equals(row.getAssetModelId())
                        || pinnedAssetIds.contains(candidate.getId())) {
                    continue;
                }
                usedAssetIds.add(candidate.getId());
                matched.add(candidate.getId());
                remaining--;
            }
            matchedByRequirement.put(row.getId(), List.copyOf(matched));
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
                    : stockByModel.getOrDefault(row.getAssetModelId(), BigDecimal.ZERO);
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
                .filter(pinnedAssetIds::contains)
                .toList();
        List<UUID> extras = contents.stream()
                .filter(Asset::isActive)
                .map(Asset::getId)
                .filter(id -> !usedAssetIds.contains(id) && !misplaced.contains(id))
                .toList();
        PackingPreviewView preview = new PackingPreviewView(
                missing.isEmpty() && misplaced.isEmpty() && extras.isEmpty(),
                satisfied,
                missing,
                extras,
                misplaced,
                consumables);

        return new Evaluation(
                preview,
                Set.copyOf(usedAssetIds),
                contents.stream().anyMatch(asset -> !asset.isActive()),
                Map.copyOf(matchedByRequirement),
                rows,
                contents,
                balances);
    }

    public record Evaluation(
            PackingPreviewView preview,
            Set<UUID> matchedAssetIds,
            boolean inactiveDirectContents,
            Map<UUID, List<UUID>> matchedAssetIdsByRequirement,
            List<PackingRequirement> requirements,
            List<Asset> directContents,
            List<ConsumableStock> balances) {}

    private Asset requireContainerForRead(UUID org, UUID id) {
        Asset a = assets.findByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new NotFoundException("Container asset not found."));
        AssetModel m = models.findByIdAndOrganizationId(a.getAssetModelId(), org)
                .orElseThrow(() -> new NotFoundException("Container asset not found."));
        if (!m.isCanContainAssets())
            throw new ValidationFailedException("Only a container-capable asset can own packing requirements.");
        return a;
    }

    private static void auth(TarpeistoPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Authentication required.");
    }

    private static void validateQuantity(BigDecimal quantity, boolean positive) {
        if (quantity == null
                || quantity.signum() < 0
                || (positive && quantity.signum() == 0)
                || quantity.scale() > 3
                || quantity.compareTo(new BigDecimal("100000000000")) >= 0)
            throw new ValidationFailedException(
                    "Quantity must be non-negative, below 100000000000, with at most three decimals.");
    }
}

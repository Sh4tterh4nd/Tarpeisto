package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.document.PackingSheetSnapshot;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import io.kellermann.tarpeisto.repository.AssetRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Takes a short, read-only, organization-scoped packing-sheet snapshot before PDF rendering. */
@Service
public class PackingSheetSnapshotService {

    private final AssetRepository assets;

    public PackingSheetSnapshotService(AssetRepository assets) {
        this.assets = assets;
    }

    @Transactional(readOnly = true)
    public PackingSheetSnapshot snapshot(UUID organizationId, UUID assetId) {
        AssetRepository.PackingSheetContainerProjection container = assets.findPackingSheetContainerProjection(
                        organizationId, assetId)
                .orElseThrow(() -> new NotFoundException("Asset not found."));
        // The header joins the model through the same organization ID, so this is both a
        // capability check and a tenant-safe read.
        if (!container.getCanContainAssets()) {
            throw new ValidationFailedException("Packing sheets are available only for containers.");
        }
        List<PackingSheetSnapshot.Requirement> requirements =
                groupRequirements(assets.findActivePackingSheetRequirements(organizationId, assetId));
        Set<String> exactCodes = requirements.stream()
                .filter(requirement -> requirement.kind() == PackingSheetSnapshot.Kind.EXACT)
                .map(PackingSheetSnapshot.Requirement::exactAssetCode)
                .collect(Collectors.toSet());
        List<PackingSheetSnapshot.ChildContainer> children =
                assets.findPackingSheetChildContainers(organizationId, assetId).stream()
                        .filter(child -> !exactCodes.contains(child.getPublicCode()))
                        .map(child -> new PackingSheetSnapshot.ChildContainer(
                                displayName(child.getIndividualName(), child.getModelName(), child.getUnitNumber()),
                                child.getModelName(),
                                child.getPublicCode()))
                        .toList();
        return new PackingSheetSnapshot(
                displayName(container.getIndividualName(), container.getModelName(), container.getUnitNumber()),
                container.getModelName(),
                container.getPublicCode(),
                container.getOrganizationName(),
                container.getContainerColor(),
                container.getUnitDescription(),
                requirements,
                children);
    }

    private static List<PackingSheetSnapshot.Requirement> groupRequirements(
            List<AssetRepository.PackingSheetRequirementProjection> projections) {
        List<PackingSheetSnapshot.Requirement> exact = new ArrayList<>();
        Map<ModelKey, PackingSheetSnapshot.Requirement> grouped = new LinkedHashMap<>();
        for (AssetRepository.PackingSheetRequirementProjection row : projections) {
            if (row.getRequirementType() == PackingRequirementType.SPECIFIC_ASSET) {
                exact.add(new PackingSheetSnapshot.Requirement(
                        PackingSheetSnapshot.Kind.EXACT,
                        requireValue(row.getModelName(), "Exact asset model is unavailable."),
                        BigDecimal.ONE,
                        null,
                        requireValue(row.getSpecificAssetCode(), "Exact asset is unavailable."),
                        displayName(row.getSpecificAssetName(), row.getModelName(), row.getSpecificAssetUnitNumber())));
                continue;
            }
            PackingSheetSnapshot.Kind kind = row.getRequirementType() == PackingRequirementType.CONSUMABLE_QUANTITY
                    ? PackingSheetSnapshot.Kind.CONSUMABLE
                    : PackingSheetSnapshot.Kind.SERIALIZED_MODEL;
            ModelKey key = new ModelKey(kind, row.getAssetModelId());
            PackingSheetSnapshot.Requirement previous = grouped.get(key);
            BigDecimal quantity = previous == null
                    ? row.getRequiredQuantity()
                    : previous.quantity().add(row.getRequiredQuantity());
            grouped.put(
                    key,
                    new PackingSheetSnapshot.Requirement(
                            kind,
                            requireValue(row.getModelName(), "Packing requirement model is unavailable."),
                            quantity,
                            kind == PackingSheetSnapshot.Kind.CONSUMABLE
                                    ? requireValue(row.getStockUnitLabel(), "Consumable stock unit is unavailable.")
                                    : null,
                            null,
                            null));
        }
        List<PackingSheetSnapshot.Requirement> result = new ArrayList<>(grouped.values());
        result.addAll(exact);
        return List.copyOf(result);
    }

    private static String displayName(String individualName, String modelName, Integer unitNumber) {
        if (individualName != null && !individualName.isBlank()) {
            return individualName;
        }
        return unitNumber == null ? modelName : modelName + " " + unitNumber;
    }

    private static String requireValue(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ValidationFailedException(message);
        }
        return value;
    }

    private record ModelKey(PackingSheetSnapshot.Kind kind, UUID modelId) {}
}

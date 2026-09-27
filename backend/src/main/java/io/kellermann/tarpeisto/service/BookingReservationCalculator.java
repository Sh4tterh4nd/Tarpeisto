package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.AssetModel;
import io.kellermann.tarpeisto.model.BookingClaimType;
import io.kellermann.tarpeisto.model.BookingLine;
import io.kellermann.tarpeisto.model.BookingLineType;
import io.kellermann.tarpeisto.model.ConsumableStock;
import io.kellermann.tarpeisto.model.PackingRequirement;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Expands each physical boundary once, reserving exact identities before interchangeable capacity. */
@Component
public class BookingReservationCalculator {
    public List<BookingClaimCandidate> expand(
            Collection<BookingLine> lines, Collection<Asset> assets, Collection<PackingRequirement> requirements) {
        return expand(lines, assets, requirements, List.of(), List.of());
    }

    public List<BookingClaimCandidate> expand(
            Collection<BookingLine> lines,
            Collection<Asset> assets,
            Collection<PackingRequirement> requirements,
            Collection<ConsumableStock> stocks,
            Collection<AssetModel> models) {
        Map<UUID, Asset> byId = new HashMap<>();
        Map<UUID, List<Asset>> children = new HashMap<>();
        for (Asset a : assets) {
            byId.put(a.getId(), a);
            if (a.getParentContainerAssetId() != null)
                children.computeIfAbsent(a.getParentContainerAssetId(), k -> new ArrayList<>())
                        .add(a);
        }
        Map<UUID, AssetModel> modelById = new HashMap<>();
        for (AssetModel m : models) modelById.put(m.getId(), m);
        Map<UUID, List<PackingRequirement>> byContainer = new HashMap<>();
        Set<UUID> pinned = new HashSet<>();
        for (PackingRequirement r : requirements)
            if (!r.isArchived()) {
                byContainer
                        .computeIfAbsent(r.getContainerAssetId(), k -> new ArrayList<>())
                        .add(r);
                if (r.getSpecificAssetId() != null) pinned.add(r.getSpecificAssetId());
            }
        Map<UUID, UUID> visited = new LinkedHashMap<>();
        Map<UUID, BookingClaimCandidate> concrete = new LinkedHashMap<>();
        List<BookingClaimCandidate> result = new ArrayList<>();
        ArrayDeque<Boundary> pending = new ArrayDeque<>();
        for (BookingLine l : lines)
            if (!l.isArchived()) {
                if (l.getLineType() == BookingLineType.CONSUMABLE) {
                    ConsumableStock source = stocks.stream()
                            .filter(stock -> stock.getId().equals(l.getConsumableStockId()))
                            .findFirst()
                            .orElse(null);
                    AssetModel model = source == null ? null : modelById.get(source.getAssetModelId());
                    Map<String, Object> snapshot = new LinkedHashMap<>();
                    if (source != null) {
                        snapshot.put("modelId", source.getAssetModelId());
                        snapshot.put("sourceContainerId", source.getContainerAssetId());
                        snapshot.put("sourceLocationId", source.getLocationId());
                    }
                    snapshot.put("modelName", model == null ? null : model.getName());
                    snapshot.put("stockUnitLabel", model == null ? null : model.getStockUnitLabel());
                    snapshot.put("quantity", l.getQuantity());
                    if (source == null && models.isEmpty() && stocks.isEmpty())
                        result.add(new BookingClaimCandidate(
                                BookingClaimType.CONSUMABLE,
                                null,
                                null,
                                l.getConsumableStockId(),
                                l.getQuantity(),
                                l.getId()));
                    else
                        result.add(new BookingClaimCandidate(
                                BookingClaimType.CONSUMABLE,
                                null,
                                null,
                                l.getConsumableStockId(),
                                l.getQuantity(),
                                l.getId(),
                                source == null ? null : source.getContainerAssetId(),
                                null,
                                snapshot));
                } else {
                    Asset a = byId.get(l.getAssetId());
                    concrete.put(
                            l.getAssetId(),
                            assetClaim(a, l.getAssetId(), l.getId(), null, null, BookingClaimType.ASSET, modelById));
                    if (l.getLineType() == BookingLineType.CONTAINER)
                        pending.add(new Boundary(l.getAssetId(), l.getId()));
                }
            }
        while (!pending.isEmpty()) {
            Boundary b = pending.removeFirst();
            if (visited.putIfAbsent(b.assetId(), b.lineId()) != null) continue;
            for (Asset child : children.getOrDefault(b.assetId(), List.of()))
                pending.add(new Boundary(child.getId(), b.lineId()));
            for (PackingRequirement r : byContainer.getOrDefault(b.assetId(), List.of()))
                if (r.getRequirementType() == PackingRequirementType.SPECIFIC_ASSET) {
                    concrete.put(
                            r.getSpecificAssetId(),
                            assetClaim(
                                    byId.get(r.getSpecificAssetId()),
                                    r.getSpecificAssetId(),
                                    b.lineId(),
                                    b.assetId(),
                                    r.getId(),
                                    BookingClaimType.ASSET,
                                    modelById));
                    pending.add(new Boundary(r.getSpecificAssetId(), b.lineId()));
                }
        }
        for (Map.Entry<UUID, UUID> boundary : visited.entrySet()) {
            UUID container = boundary.getKey(), line = boundary.getValue();
            Map<UUID, BigDecimal> residual = new HashMap<>();
            Map<UUID, UUID> requirementIds = new HashMap<>();
            for (PackingRequirement r : byContainer.getOrDefault(container, List.of()))
                if (r.getRequirementType() == PackingRequirementType.MODEL_QUANTITY) {
                    residual.put(r.getAssetModelId(), r.getRequiredQuantity());
                    requirementIds.put(r.getAssetModelId(), r.getId());
                }
            for (Asset child : children.getOrDefault(container, List.of())) {
                AssetModel model = modelById.get(child.getAssetModelId());
                BigDecimal remaining = residual.getOrDefault(child.getAssetModelId(), BigDecimal.ZERO);
                boolean matches = remaining.signum() > 0
                        && child.isActive()
                        && (model == null || !model.isArchived())
                        && !pinned.contains(child.getId())
                        && !concrete.containsKey(child.getId());
                BookingClaimType type = matches && (model == null || !model.isCanContainAssets())
                        ? BookingClaimType.FLEXIBLE_ASSET
                        : BookingClaimType.ASSET;
                concrete.putIfAbsent(
                        child.getId(),
                        assetClaim(
                                child,
                                child.getId(),
                                line,
                                container,
                                matches ? requirementIds.get(child.getAssetModelId()) : null,
                                type,
                                modelById));
                if (matches) residual.put(child.getAssetModelId(), remaining.subtract(BigDecimal.ONE));
            }
            for (Map.Entry<UUID, BigDecimal> demand : residual.entrySet())
                if (demand.getValue().signum() > 0)
                    result.add(new BookingClaimCandidate(
                            BookingClaimType.MODEL_CAPACITY,
                            null,
                            demand.getKey(),
                            null,
                            demand.getValue(),
                            line,
                            container,
                            requirementIds.get(demand.getKey()),
                            modelSnapshot(modelById.get(demand.getKey()), demand.getKey(), demand.getValue())));
            for (ConsumableStock stock : stocks)
                if (container.equals(stock.getContainerAssetId())
                        && stock.getQuantity().signum() > 0) {
                    AssetModel model = modelById.get(stock.getAssetModelId());
                    Map<String, Object> snapshot = new LinkedHashMap<>();
                    snapshot.put("modelId", stock.getAssetModelId());
                    snapshot.put("quantity", stock.getQuantity());
                    PackingRequirement stockRequirement = byContainer.getOrDefault(container, List.of()).stream()
                            .filter(req -> req.getRequirementType() == PackingRequirementType.CONSUMABLE_QUANTITY
                                    && req.getAssetModelId().equals(stock.getAssetModelId()))
                            .findFirst()
                            .orElse(null);
                    snapshot.put(
                            "requiredQuantity",
                            stockRequirement == null ? null : stockRequirement.getRequiredQuantity());
                    snapshot.put("modelName", model == null ? null : model.getName());
                    snapshot.put("stockUnitLabel", model == null ? null : model.getStockUnitLabel());
                    result.add(new BookingClaimCandidate(
                            BookingClaimType.CARRIED_CONSUMABLE,
                            null,
                            null,
                            stock.getId(),
                            stock.getQuantity(),
                            line,
                            container,
                            stockRequirement == null ? null : stockRequirement.getId(),
                            snapshot));
                }
        }
        result.addAll(concrete.values());
        return List.copyOf(result);
    }

    private Map<String, Object> modelSnapshot(AssetModel model, UUID modelId, BigDecimal quantity) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("modelId", modelId);
        snapshot.put("modelName", model == null ? null : model.getName());
        snapshot.put("requiredQuantity", quantity);
        return snapshot;
    }

    private BookingClaimCandidate assetClaim(
            Asset a,
            UUID assetId,
            UUID line,
            UUID container,
            UUID requirement,
            BookingClaimType type,
            Map<UUID, AssetModel> models) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("assetId", assetId);
        if (a != null) {
            snapshot.put("assetCode", a.getPublicCode());
            snapshot.put("individualName", a.getIndividualName());
            snapshot.put("unitNumber", a.getUnitNumber());
            snapshot.put("modelId", a.getAssetModelId());
            AssetModel m = models.get(a.getAssetModelId());
            snapshot.put("modelName", m == null ? null : m.getName());
        }
        return new BookingClaimCandidate(
                type, assetId, null, null, BigDecimal.ONE, line, container, requirement, snapshot);
    }

    private record Boundary(UUID assetId, UUID lineId) {}
}

package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.PackingConflictException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.ConsumableStock;
import io.kellermann.tarpeisto.model.PackingContentStatus;
import io.kellermann.tarpeisto.model.PackingRequirement;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import io.kellermann.tarpeisto.repository.JdbcPackingContentsRepository;
import io.kellermann.tarpeisto.repository.JdbcPackingContentsRepository.AssetIdentity;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Authoritative current packing grouping with bounded status-first serialized identity pages. */
@Service
public class PackingContentsService {
    private static final int PAGE_SIZE = 100;
    private final PackingEvaluationService evaluation;
    private final JdbcPackingContentsRepository identities;

    public PackingContentsService(PackingEvaluationService evaluation, JdbcPackingContentsRepository identities) {
        this.evaluation = evaluation;
        this.identities = identities;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PackingContentsView get(TarpeistoPrincipal principal, UUID container, String cursor) {
        var current = evaluation.evaluate(principal, container, null);
        String revision = revision(current);
        Cursor anchor = decode(principal.organizationId(), container, revision, cursor);
        Map<UUID, UUID> assignments = new HashMap<>();
        current.matchedAssetIdsByRequirement()
                .forEach((requirement, assets) -> assets.forEach(asset -> assignments.put(asset, requirement)));
        Set<UUID> misplaced = Set.copyOf(current.preview().misplacedAssetIds());
        Comparator<Asset> assetOrder = Comparator.comparingInt((Asset asset) -> rank(asset, assignments))
                .thenComparing(asset -> asset.getId().toString());
        List<Asset> remainingAssets = anchor.assetsDone
                ? List.of()
                : current.directContents().stream()
                        .filter(asset -> anchor.assetId == null
                                || rank(asset, assignments) > anchor.assetRank
                                || rank(asset, assignments) == anchor.assetRank
                                        && asset.getId().toString().compareTo(anchor.assetId.toString()) > 0)
                        .sorted(assetOrder)
                        .limit(PAGE_SIZE + 1)
                        .toList();
        List<Asset> pageAssets = remainingAssets.subList(0, Math.min(PAGE_SIZE, remainingAssets.size()));
        List<PackingRequirement> remainingRequirements = anchor.requirementsDone
                ? List.of()
                : current.requirements().stream()
                        .filter(row -> anchor.requirementId == null
                                || row.getDisplayOrder() > anchor.requirementOrder
                                || row.getDisplayOrder() == anchor.requirementOrder
                                        && row.getId().toString().compareTo(anchor.requirementId.toString()) > 0)
                        .sorted(Comparator.comparingInt(PackingRequirement::getDisplayOrder)
                                .thenComparing(row -> row.getId().toString()))
                        .limit(PAGE_SIZE + 1)
                        .toList();
        List<PackingRequirement> pageRequirements =
                remainingRequirements.subList(0, Math.min(PAGE_SIZE, remainingRequirements.size()));
        Set<UUID> assetIds = new HashSet<>(pageAssets.stream().map(Asset::getId).toList());
        pageRequirements.stream()
                .map(PackingRequirement::getSpecificAssetId)
                .filter(Objects::nonNull)
                .forEach(assetIds::add);
        Map<UUID, AssetIdentity> namedAssets =
                identities.assets(principal.organizationId(), List.copyOf(assetIds)).stream()
                        .collect(Collectors.toMap(AssetIdentity::assetId, asset -> asset));
        List<ConsumableStock> remainingStocks = anchor.stocksDone
                ? List.of()
                : current.balances().stream()
                        .filter(balance -> anchor.stockModelId == null
                                || balance.getAssetModelId().toString().compareTo(anchor.stockModelId.toString()) > 0)
                        .sorted(Comparator.comparing(
                                balance -> balance.getAssetModelId().toString()))
                        .limit(PAGE_SIZE + 1)
                        .toList();
        List<ConsumableStock> pageStocks = remainingStocks.subList(0, Math.min(PAGE_SIZE, remainingStocks.size()));
        Set<UUID> modelIds = new HashSet<>();
        pageRequirements.stream()
                .map(PackingRequirement::getAssetModelId)
                .filter(Objects::nonNull)
                .forEach(modelIds::add);
        pageStocks.forEach(balance -> modelIds.add(balance.getAssetModelId()));
        var namedModels = identities.models(principal.organizationId(), List.copyOf(modelIds)).stream()
                .collect(Collectors.toMap(JdbcPackingContentsRepository.ModelIdentity::assetModelId, model -> model));
        Map<UUID, BigDecimal> consumables = current.preview().consumables().stream()
                .collect(Collectors.toMap(
                        PackingPreviewView.ConsumableRequirementStatus::requirementId,
                        PackingPreviewView.ConsumableRequirementStatus::observedQuantity));
        List<PackingContentsView.Requirement> requirements = pageRequirements.stream()
                .map(row -> {
                    BigDecimal present = row.getRequirementType() == PackingRequirementType.CONSUMABLE_QUANTITY
                            ? consumables.get(row.getId())
                            : BigDecimal.valueOf(current.matchedAssetIdsByRequirement()
                                    .getOrDefault(row.getId(), List.of())
                                    .size());
                    var exact = row.getSpecificAssetId() == null
                            ? null
                            : identity(namedAssets.get(row.getSpecificAssetId()));
                    var model = row.getAssetModelId() == null ? null : namedModels.get(row.getAssetModelId());
                    return new PackingContentsView.Requirement(
                            row.getId(),
                            row.getRequirementType(),
                            row.getAssetModelId(),
                            model == null ? exact == null ? null : exact.assetModelName() : model.name(),
                            model == null ? null : model.unitLabel(),
                            row.getRequiredQuantity(),
                            present,
                            row.getRequiredQuantity().subtract(present).max(BigDecimal.ZERO),
                            current.preview().satisfiedRequirementIds().contains(row.getId()),
                            exact);
                })
                .toList();
        List<PackingContentsView.Entry> entries = pageAssets.stream()
                .map(asset -> new PackingContentsView.Entry(
                        identity(namedAssets.get(asset.getId())),
                        !asset.isActive()
                                ? PackingContentStatus.INACTIVE
                                : assignments.containsKey(asset.getId())
                                        ? PackingContentStatus.MATCHED
                                        : misplaced.contains(asset.getId())
                                                ? PackingContentStatus.MISPLACED
                                                : PackingContentStatus.EXTRA,
                        assignments.get(asset.getId())))
                .toList();
        List<PackingContentsView.Consumable> stocks = pageStocks.stream()
                .map(balance -> {
                    var model = namedModels.get(balance.getAssetModelId());
                    return new PackingContentsView.Consumable(
                            balance.getAssetModelId(),
                            model.name(),
                            model.unitLabel(),
                            balance.getQuantity(),
                            balance.isArchived());
                })
                .toList();
        boolean assetsDone = remainingAssets.size() <= PAGE_SIZE;
        boolean requirementsDone = remainingRequirements.size() <= PAGE_SIZE;
        Asset lastAsset = pageAssets.isEmpty() ? null : pageAssets.getLast();
        PackingRequirement lastRequirement = pageRequirements.isEmpty() ? null : pageRequirements.getLast();
        boolean stocksDone = remainingStocks.size() <= PAGE_SIZE;
        ConsumableStock lastStock = pageStocks.isEmpty() ? null : pageStocks.getLast();
        String next = assetsDone && requirementsDone && stocksDone
                ? null
                : encode(
                        principal.organizationId(),
                        container,
                        revision,
                        new Cursor(
                                lastAsset == null ? anchor.assetRank : rank(lastAsset, assignments),
                                lastAsset == null ? anchor.assetId : lastAsset.getId(),
                                assetsDone,
                                lastRequirement == null ? anchor.requirementOrder : lastRequirement.getDisplayOrder(),
                                lastRequirement == null ? anchor.requirementId : lastRequirement.getId(),
                                requirementsDone,
                                lastStock == null ? anchor.stockModelId : lastStock.getAssetModelId(),
                                stocksDone));
        return new PackingContentsView(
                container,
                current.preview().complete() && !current.inactiveDirectContents(),
                current.directContents().size(),
                current.matchedAssetIds().size(),
                current.preview().extraAssetIds().size(),
                misplaced.size(),
                (int) current.directContents().stream()
                        .filter(asset -> !asset.isActive())
                        .count(),
                current.requirements().size(),
                current.balances().size(),
                requirements,
                entries,
                stocks,
                next);
    }

    private static int rank(Asset asset, Map<UUID, UUID> assignments) {
        return asset.isActive() && assignments.containsKey(asset.getId()) ? 1 : 0;
    }

    private static PackingContentsView.Identity identity(AssetIdentity asset) {
        if (asset == null) return null;
        return new PackingContentsView.Identity(
                asset.assetId(),
                asset.publicCode(),
                asset.displayName(),
                asset.assetModelId(),
                asset.assetModelName(),
                asset.active());
    }

    private static String revision(PackingEvaluationService.Evaluation current) {
        List<String> facts = new java.util.ArrayList<>();
        current.requirements().forEach(row -> facts.add("requirement:" + row.getId() + ":" + row.getVersion()));
        current.directContents().forEach(row -> facts.add("asset:" + row.getId() + ":" + row.getVersion()));
        current.balances().forEach(row -> facts.add("stock:" + row.getId() + ":" + row.getVersion()));
        current.matchedAssetIdsByRequirement()
                .forEach((requirement, assets) ->
                        assets.forEach(asset -> facts.add("match:" + requirement + ":" + asset)));
        current.preview().misplacedAssetIds().forEach(asset -> facts.add("misplaced:" + asset));
        facts.sort(String::compareTo);
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(String.join("\n", facts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable.", unavailable);
        }
    }

    private record Cursor(
            int assetRank,
            UUID assetId,
            boolean assetsDone,
            int requirementOrder,
            UUID requirementId,
            boolean requirementsDone,
            UUID stockModelId,
            boolean stocksDone) {}

    private static String encode(UUID org, UUID container, String revision, Cursor cursor) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        ("packing-contents:v1:" + org + ":" + container + ":" + revision + ":" + cursor.assetRank + ":"
                                        + cursor.assetId + ":" + cursor.assetsDone + ":" + cursor.requirementOrder + ":"
                                        + cursor.requirementId + ":" + cursor.requirementsDone + ":"
                                        + cursor.stockModelId + ":" + cursor.stocksDone)
                                .getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(UUID org, UUID container, String revision, String encoded) {
        if (encoded == null) return new Cursor(0, null, false, 0, null, false, null, false);
        try {
            if (encoded.length() > 512) throw new IllegalArgumentException();
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String prefix = "packing-contents:v1:" + org + ":" + container + ":";
            if (!decoded.startsWith(prefix)) throw new IllegalArgumentException();
            String rest = decoded.substring(prefix.length());
            int separator = rest.indexOf(':');
            if (separator != 64 || !rest.substring(0, separator).matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException();
            if (!rest.substring(0, separator).equals(revision))
                throw new PackingConflictException("Packing contents changed. Refresh to load the current contents.");
            String[] values = rest.substring(separator + 1).split(":", -1);
            if (values.length != 8
                    || !(values[2].equals("true") || values[2].equals("false"))
                    || !(values[5].equals("true") || values[5].equals("false"))
                    || !(values[7].equals("true") || values[7].equals("false"))) throw new IllegalArgumentException();
            Cursor result = new Cursor(
                    Integer.parseInt(values[0]),
                    values[1].equals("null") ? null : UUID.fromString(values[1]),
                    Boolean.parseBoolean(values[2]),
                    Integer.parseInt(values[3]),
                    values[4].equals("null") ? null : UUID.fromString(values[4]),
                    Boolean.parseBoolean(values[5]),
                    values[6].equals("null") ? null : UUID.fromString(values[6]),
                    Boolean.parseBoolean(values[7]));
            if (result.assetRank < 0
                    || result.assetRank > 1
                    || result.requirementOrder < 0
                    || !encode(org, container, revision, result).equals(encoded)) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException("Invalid packing contents cursor.");
        }
    }
}

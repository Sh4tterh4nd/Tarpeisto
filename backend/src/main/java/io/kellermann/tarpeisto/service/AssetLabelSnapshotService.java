package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.document.AssetLabelEntry;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.repository.AssetRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a compact, immutable label snapshot in a short read-only transaction before document
 * rendering begins.
 */
@Service
public class AssetLabelSnapshotService {

    private static final int MAX_ASSETS_PER_REQUEST = 500;
    private static final String DEFAULT_CATEGORY_NAME = "Default";
    private static final String DEFAULT_CATEGORY_COLOR = "#5B6472";

    private final AssetRepository assetRepository;

    public AssetLabelSnapshotService(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    @Transactional(readOnly = true)
    public List<AssetLabelEntry> snapshot(UUID organizationId, List<UUID> assetIds) {
        validateAssetIds(assetIds);
        Map<UUID, AssetRepository.AssetLabelProjection> projectionsById = new HashMap<>();
        for (AssetRepository.AssetLabelProjection projection :
                assetRepository.findLabelProjectionsByOrganizationIdAndIdIn(organizationId, assetIds)) {
            projectionsById.put(projection.getAssetId(), projection);
        }

        return assetIds.stream()
                .map(assetId -> entryFor(assetId, projectionsById))
                .toList();
    }

    private static void validateAssetIds(List<UUID> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            throw new ValidationFailedException("At least one assetId is required.");
        }
        if (assetIds.size() > MAX_ASSETS_PER_REQUEST) {
            throw new ValidationFailedException(
                    "At most " + MAX_ASSETS_PER_REQUEST + " assets can be exported at once.");
        }
        Set<UUID> seen = new HashSet<>();
        for (UUID assetId : assetIds) {
            if (assetId == null || !seen.add(assetId)) {
                throw new ValidationFailedException("assetIds must contain each asset at most once.");
            }
        }
    }

    private static AssetLabelEntry entryFor(
            UUID assetId, Map<UUID, AssetRepository.AssetLabelProjection> projectionsById) {
        AssetRepository.AssetLabelProjection projection = projectionsById.get(assetId);
        if (projection == null) {
            throw new NotFoundException("Asset not found.");
        }
        return new AssetLabelEntry(
                projection.getPublicCode(),
                projection.getModelName(),
                projection.getIndividualName(),
                projection.getUnitNumber(),
                projection.getCategoryName() == null ? DEFAULT_CATEGORY_NAME : projection.getCategoryName(),
                projection.getCategoryColor() == null ? DEFAULT_CATEGORY_COLOR : projection.getCategoryColor());
    }
}

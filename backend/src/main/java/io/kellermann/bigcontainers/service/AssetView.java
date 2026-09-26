package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.Condition;
import io.kellermann.bigcontainers.model.LifecycleState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read projection of a physical {@code Asset} for the catalog API (specification section 8).
 *
 * <p>{@link #displayName} is the derived label from specification section 8.2: {@code
 * individualName} when set, otherwise {@code "<Model Name> <unit number>"}. {@link
 * #metadataIncomplete} answers specification section 7.2's "metadata incomplete" status, computed
 * (not stored) as "at least one active custom field of this asset's model has no value here" -
 * see {@code AssetRepository#isMetadataIncomplete}.
 */
public record AssetView(
        UUID id,
        UUID assetModelId,
        String assetModelName,
        String publicCode,
        int unitNumber,
        String individualName,
        String displayName,
        Condition condition,
        LifecycleState lifecycleState,
        LocalDate purchaseDate,
        boolean archived,
        boolean metadataIncomplete,
        List<AssetCustomFieldValueView> values,
        Instant createdAt,
        Instant updatedAt) {}

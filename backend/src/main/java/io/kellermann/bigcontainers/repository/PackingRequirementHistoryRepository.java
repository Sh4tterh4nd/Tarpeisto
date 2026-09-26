package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.PackingRequirementHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PackingRequirementHistoryRepository extends JpaRepository<PackingRequirementHistory, UUID> {
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM packing_requirement_history
                WHERE organization_id = :organizationId
                  AND (details::jsonb #>> '{before,assetModelId}' = CAST(:assetModelId AS text)
                    OR details::jsonb #>> '{after,assetModelId}' = CAST(:assetModelId AS text))
            )
            """, nativeQuery = true)
    boolean hasAssetModelHistory(
            @Param("organizationId") UUID organizationId, @Param("assetModelId") UUID assetModelId);
}

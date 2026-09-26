package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AuditTask;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditTaskRepository extends JpaRepository<AuditTask, UUID> {
    @Query(value = """
            WITH RECURSIVE ancestry AS (
                SELECT id, parent_container_asset_id FROM physical_asset WHERE organization_id = :org AND id = :assetId
                UNION
                SELECT parent.id, parent.parent_container_asset_id FROM physical_asset parent
                JOIN ancestry child ON parent.id = child.parent_container_asset_id WHERE parent.organization_id = :org
            )
            SELECT EXISTS (SELECT 1 FROM audit_task task JOIN ancestry asset ON asset.id = task.container_asset_id
                           WHERE task.organization_id = :org AND task.state <> 'COMPLETED')
            """, nativeQuery = true)
    boolean hasPendingAudit(@Param("org") UUID organizationId, @Param("assetId") UUID assetId);

    List<AuditTask> findAllByOrganizationIdAndAuditBatchIdOrderById(UUID org, UUID batchId);

    java.util.Optional<AuditTask> findByOrganizationIdAndId(UUID org, UUID id);

    List<AuditTask> findAllByOrganizationIdAndContainerAssetIdOrderByCreatedAtDesc(UUID org, UUID containerAssetId);
}

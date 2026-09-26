package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AuditScan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditScanRepository extends JpaRepository<AuditScan, UUID> {
    List<AuditScan> findAllByOrganizationIdAndAuditIdOrderByScannedAtAsc(UUID organizationId, UUID auditId);

    Optional<AuditScan> findByOrganizationIdAndAuditIdAndOperationId(
            UUID organizationId, UUID auditId, UUID operationId);

    Optional<AuditScan> findByIdAndOrganizationIdAndAuditId(UUID id, UUID organizationId, UUID auditId);

    Optional<AuditScan> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<AuditScan> findAllByOrganizationIdAndAuditIdInAndAssetIdAndUndoneAtIsNull(
            UUID organizationId, List<UUID> auditIds, UUID assetId);
}

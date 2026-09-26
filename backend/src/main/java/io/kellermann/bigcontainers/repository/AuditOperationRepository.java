package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AuditOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditOperationRepository extends JpaRepository<AuditOperation, UUID> {
    Optional<AuditOperation> findByOrganizationIdAndAuditIdAndOperationId(
            UUID organizationId, UUID auditId, UUID operationId);
}

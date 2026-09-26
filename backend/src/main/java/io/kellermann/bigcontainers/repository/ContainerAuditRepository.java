package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.ContainerAudit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContainerAuditRepository extends JpaRepository<ContainerAudit, UUID> {
    Optional<ContainerAudit> findByOrganizationIdAndAuditTaskId(UUID organizationId, UUID auditTaskId);

    Optional<ContainerAudit> findByOrganizationIdAndId(UUID organizationId, UUID id);

    List<ContainerAudit> findAllByOrganizationIdAndAuditBatchIdOrderById(UUID organizationId, UUID batchId);
}

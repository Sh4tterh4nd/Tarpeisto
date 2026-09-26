package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AuditTask;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditTaskRepository extends JpaRepository<AuditTask, UUID> {
    List<AuditTask> findAllByOrganizationIdAndAuditBatchIdOrderById(UUID org, UUID batchId);
}

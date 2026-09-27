package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.AuditFinding;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditFindingRepository extends JpaRepository<AuditFinding, UUID> {
    List<AuditFinding> findAllByOrganizationIdAndAuditIdOrderById(UUID organizationId, UUID auditId);

    List<AuditFinding> findAllByOrganizationIdOrderByRecordedAtDesc(UUID organizationId);

    java.util.Optional<AuditFinding> findByIdAndOrganizationId(UUID id, UUID organizationId);
}

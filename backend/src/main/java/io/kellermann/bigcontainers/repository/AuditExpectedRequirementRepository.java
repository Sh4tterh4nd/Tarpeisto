package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AuditExpectedRequirement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditExpectedRequirementRepository extends JpaRepository<AuditExpectedRequirement, UUID> {
    List<AuditExpectedRequirement> findAllByOrganizationIdAndAuditIdOrderByDisplayOrderAsc(
            UUID organizationId, UUID auditId);

    Optional<AuditExpectedRequirement> findByIdAndOrganizationIdAndAuditId(UUID id, UUID organizationId, UUID auditId);
}

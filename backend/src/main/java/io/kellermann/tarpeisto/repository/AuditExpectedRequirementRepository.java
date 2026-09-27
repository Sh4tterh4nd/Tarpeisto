package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.AuditExpectedRequirement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditExpectedRequirementRepository extends JpaRepository<AuditExpectedRequirement, UUID> {
    List<AuditExpectedRequirement> findAllByOrganizationIdAndAuditIdOrderByDisplayOrderAsc(
            UUID organizationId, UUID auditId);

    Optional<AuditExpectedRequirement> findByIdAndOrganizationIdAndAuditId(UUID id, UUID organizationId, UUID auditId);
}

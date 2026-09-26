package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AuditConsumableObservation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditConsumableObservationRepository extends JpaRepository<AuditConsumableObservation, UUID> {
    List<AuditConsumableObservation> findAllByOrganizationIdAndAuditId(UUID organizationId, UUID auditId);

    Optional<AuditConsumableObservation> findByOrganizationIdAndAuditIdAndExpectedId(
            UUID organizationId, UUID auditId, UUID expectedId);
}

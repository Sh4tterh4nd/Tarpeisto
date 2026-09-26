package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AuditBatch;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditBatchRepository extends JpaRepository<AuditBatch, UUID> {
    Optional<AuditBatch> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<AuditBatch> findByOrganizationIdAndBookingId(UUID org, UUID bookingId);
}

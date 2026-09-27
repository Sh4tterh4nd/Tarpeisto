package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.AuditBatch;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditBatchRepository extends JpaRepository<AuditBatch, UUID> {
    Optional<AuditBatch> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<AuditBatch> findByOrganizationIdAndBookingId(UUID org, UUID bookingId);
}

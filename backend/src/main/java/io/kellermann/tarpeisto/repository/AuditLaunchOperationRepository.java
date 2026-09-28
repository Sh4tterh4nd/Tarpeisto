package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.AuditLaunchOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLaunchOperationRepository extends JpaRepository<AuditLaunchOperation, UUID> {
    Optional<AuditLaunchOperation> findByOrganizationIdAndOperationId(UUID organizationId, UUID operationId);
}

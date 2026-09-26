package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.CheckoutReturnOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutReturnOperationRepository extends JpaRepository<CheckoutReturnOperation, UUID> {
    Optional<CheckoutReturnOperation> findByIdAndOrganizationId(UUID id, UUID organizationId);
}

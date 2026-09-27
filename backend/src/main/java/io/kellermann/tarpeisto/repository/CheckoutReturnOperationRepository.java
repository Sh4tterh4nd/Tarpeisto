package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.CheckoutReturnOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutReturnOperationRepository extends JpaRepository<CheckoutReturnOperation, UUID> {
    Optional<CheckoutReturnOperation> findByIdAndOrganizationId(UUID id, UUID organizationId);
}

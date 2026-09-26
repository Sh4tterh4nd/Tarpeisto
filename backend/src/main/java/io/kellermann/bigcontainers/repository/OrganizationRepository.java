package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.Organization;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByNameIgnoreCase(String name);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<Organization> findWithLockById(UUID id);
}

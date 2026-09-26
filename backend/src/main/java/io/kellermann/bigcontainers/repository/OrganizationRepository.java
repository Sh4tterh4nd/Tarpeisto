package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.Organization;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByNameIgnoreCase(String name);
}

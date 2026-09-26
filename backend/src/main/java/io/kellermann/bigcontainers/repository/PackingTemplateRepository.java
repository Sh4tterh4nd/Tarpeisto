package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.PackingTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackingTemplateRepository extends JpaRepository<PackingTemplate, UUID> {
    Optional<PackingTemplate> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<PackingTemplate> findAllByOrganizationIdOrderByNameAsc(UUID organizationId);
}

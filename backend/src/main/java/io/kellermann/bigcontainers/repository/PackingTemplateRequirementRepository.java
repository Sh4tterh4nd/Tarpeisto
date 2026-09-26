package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.PackingTemplateRequirement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackingTemplateRequirementRepository extends JpaRepository<PackingTemplateRequirement, UUID> {
    Optional<PackingTemplateRequirement> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<PackingTemplateRequirement> findAllByOrganizationIdAndPackingTemplateIdOrderByDisplayOrderAsc(
            UUID org, UUID templateId);

    boolean existsByOrganizationIdAndAssetModelId(UUID organizationId, UUID assetModelId);
}

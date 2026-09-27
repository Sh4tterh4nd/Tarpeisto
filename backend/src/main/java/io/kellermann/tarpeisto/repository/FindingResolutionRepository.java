package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.FindingResolution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingResolutionRepository extends JpaRepository<FindingResolution, UUID> {
    Optional<FindingResolution> findByOrganizationIdAndFindingId(UUID organizationId, UUID findingId);

    Optional<FindingResolution> findByOrganizationIdAndOperationId(UUID organizationId, UUID operationId);

    List<FindingResolution> findAllByOrganizationIdOrderByResolvedAtDesc(UUID organizationId);
}

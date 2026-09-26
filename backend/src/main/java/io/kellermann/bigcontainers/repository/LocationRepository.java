package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.Location;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, UUID> {
    Optional<Location> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Location> findAllByOrganizationIdOrderByNameAsc(UUID organizationId);

    boolean existsByOrganizationIdAndParentLocationId(UUID organizationId, UUID parentLocationId);
}

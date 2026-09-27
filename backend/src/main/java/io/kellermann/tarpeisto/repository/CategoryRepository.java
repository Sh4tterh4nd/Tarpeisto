package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Category> findAllByOrganizationIdOrderByNameAsc(UUID organizationId);

    Optional<Category> findByOrganizationIdAndNameIgnoreCaseAndArchivedAtIsNull(UUID organizationId, String name);
}

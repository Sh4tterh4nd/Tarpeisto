package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AssetModel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetModelRepository extends JpaRepository<AssetModel, UUID> {

    Optional<AssetModel> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<AssetModel> findAllByOrganizationIdOrderByNameAsc(UUID organizationId);

    List<AssetModel> findAllByOrganizationIdAndCategoryId(UUID organizationId, UUID categoryId);

    boolean existsByOrganizationIdAndCategoryId(UUID organizationId, UUID categoryId);

    Optional<AssetModel> findByOrganizationIdAndNameIgnoreCaseAndArchivedAtIsNull(UUID organizationId, String name);
}

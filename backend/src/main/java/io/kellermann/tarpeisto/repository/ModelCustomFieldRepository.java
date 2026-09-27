package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.ModelCustomField;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCustomFieldRepository extends JpaRepository<ModelCustomField, UUID> {

    Optional<ModelCustomField> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ModelCustomField> findAllByOrganizationIdAndAssetModelIdOrderByDisplayOrderAsc(
            UUID organizationId, UUID assetModelId);

    Optional<ModelCustomField> findByAssetModelIdAndNameIgnoreCaseAndArchivedAtIsNull(UUID assetModelId, String name);

    boolean existsByAssetModelId(UUID assetModelId);
}

package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AssetCustomFieldValue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetCustomFieldValueRepository extends JpaRepository<AssetCustomFieldValue, UUID> {

    List<AssetCustomFieldValue> findAllByOrganizationIdAndAssetId(UUID organizationId, UUID assetId);

    Optional<AssetCustomFieldValue> findByAssetIdAndModelCustomFieldId(UUID assetId, UUID modelCustomFieldId);

    /**
     * Used by {@code ModelCustomFieldService} to enforce specification section 7.2: "Changing a
     * field's datatype after values exist is prohibited."
     */
    boolean existsByModelCustomFieldId(UUID modelCustomFieldId);
}

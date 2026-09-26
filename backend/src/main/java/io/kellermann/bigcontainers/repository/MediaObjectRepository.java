package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.MediaObject;
import io.kellermann.bigcontainers.model.MediaPurpose;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaObjectRepository extends JpaRepository<MediaObject, UUID> {
    Optional<MediaObject> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<MediaObject> findByOrganizationIdAndAssetModelIdAndPurposeAndArchivedAtIsNull(
            UUID organizationId, UUID assetModelId, MediaPurpose purpose);

    Optional<MediaObject> findByOrganizationIdAndAssetIdAndPurposeAndArchivedAtIsNull(
            UUID organizationId, UUID assetId, MediaPurpose purpose);

    List<MediaObject> findAllByOrganizationIdAndAssetIdAndPurposeAndArchivedAtIsNullOrderByDisplayOrderAsc(
            UUID organizationId, UUID assetId, MediaPurpose purpose);
}

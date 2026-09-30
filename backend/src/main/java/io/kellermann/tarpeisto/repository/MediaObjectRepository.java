package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.MediaObject;
import io.kellermann.tarpeisto.model.MediaPurpose;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaObjectRepository extends JpaRepository<MediaObject, UUID> {
    Optional<MediaObject> findByOrganizationIdAndUploadOperationId(UUID organizationId, UUID uploadOperationId);

    List<MediaObject> findAllByOrganizationIdAndAuditIdOrderByCreatedAtAsc(UUID organizationId, UUID auditId);

    List<MediaObject> findAllByOrganizationIdAndFindingIdOrderByCreatedAtAsc(UUID organizationId, UUID findingId);

    Optional<MediaObject> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<MediaObject> findByOrganizationIdAndAssetModelIdAndPurposeAndArchivedAtIsNull(
            UUID organizationId, UUID assetModelId, MediaPurpose purpose);

    Optional<MediaObject> findByOrganizationIdAndAssetIdAndPurposeAndArchivedAtIsNull(
            UUID organizationId, UUID assetId, MediaPurpose purpose);

    List<MediaObject> findAllByOrganizationIdAndAssetIdAndPurposeAndArchivedAtIsNullOrderByDisplayOrderAsc(
            UUID organizationId, UUID assetId, MediaPurpose purpose);
}

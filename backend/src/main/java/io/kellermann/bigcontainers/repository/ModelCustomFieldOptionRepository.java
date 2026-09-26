package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.ModelCustomFieldOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCustomFieldOptionRepository extends JpaRepository<ModelCustomFieldOption, UUID> {

    Optional<ModelCustomFieldOption> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ModelCustomFieldOption> findAllByOrganizationIdAndModelCustomFieldIdOrderByDisplayOrderAsc(
            UUID organizationId, UUID modelCustomFieldId);

    Optional<ModelCustomFieldOption> findByModelCustomFieldIdAndValueIgnoreCaseAndArchivedAtIsNull(
            UUID modelCustomFieldId, String value);
}

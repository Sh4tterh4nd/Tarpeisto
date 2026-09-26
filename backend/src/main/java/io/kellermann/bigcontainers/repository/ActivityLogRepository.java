package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.ActivityLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID> {

    List<ActivityLog> findAllByOrganizationIdOrderByOccurredAtDesc(UUID organizationId);
}

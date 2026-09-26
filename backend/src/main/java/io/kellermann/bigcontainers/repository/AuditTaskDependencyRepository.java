package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AuditTaskDependency;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditTaskDependencyRepository extends JpaRepository<AuditTaskDependency, AuditTaskDependency.Key> {
    java.util.List<AuditTaskDependency> findAllByOrganizationIdAndIdTaskIdIn(
            java.util.UUID organizationId, java.util.Collection<java.util.UUID> taskIds);
}

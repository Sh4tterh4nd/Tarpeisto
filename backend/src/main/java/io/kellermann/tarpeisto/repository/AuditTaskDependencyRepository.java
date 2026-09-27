package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.AuditTaskDependency;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditTaskDependencyRepository extends JpaRepository<AuditTaskDependency, AuditTaskDependency.Key> {
    java.util.List<AuditTaskDependency> findAllByOrganizationIdAndIdTaskIdIn(
            java.util.UUID organizationId, java.util.Collection<java.util.UUID> taskIds);

    java.util.List<AuditTaskDependency> findAllByOrganizationIdAndIdDependsOnTaskId(
            java.util.UUID organizationId, java.util.UUID dependsOnTaskId);
}

package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "audit_task_dependency")
public class AuditTaskDependency {
    @EmbeddedId
    private Key id;

    @Column(name = "organization_id")
    private UUID organizationId;

    protected AuditTaskDependency() {}

    public AuditTaskDependency(UUID org, UUID task, UUID dependsOn) {
        organizationId = org;
        id = new Key(task, dependsOn);
    }

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "audit_task_id")
        private UUID taskId;

        @Column(name = "depends_on_audit_task_id")
        private UUID dependsOnTaskId;

        protected Key() {}

        public Key(UUID taskId, UUID dependsOnTaskId) {
            this.taskId = taskId;
            this.dependsOnTaskId = dependsOnTaskId;
        }

        public UUID getTaskId() {
            return taskId;
        }

        public UUID getDependsOnTaskId() {
            return dependsOnTaskId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(taskId, that.taskId) && Objects.equals(dependsOnTaskId, that.dependsOnTaskId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(taskId, dependsOnTaskId);
        }
    }

    public Key getId() {
        return id;
    }
}

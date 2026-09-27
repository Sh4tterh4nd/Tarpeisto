package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_consumable_observation")
public class AuditConsumableObservation {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "container_audit_id")
    private UUID auditId;

    @Column(name = "audit_expected_requirement_id")
    private UUID expectedId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AuditConsumableStatus status;

    @Column(name = "observed_quantity")
    private BigDecimal observedQuantity;

    @Column(name = "client_operation_id")
    private UUID operationId;

    @Column(name = "recorded_by_user_id")
    private UUID actor;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    protected AuditConsumableObservation() {}

    public AuditConsumableObservation(
            UUID id,
            UUID org,
            UUID audit,
            UUID expected,
            AuditConsumableStatus status,
            BigDecimal observed,
            UUID op,
            UUID actor,
            Instant at) {
        this.id = id;
        organizationId = org;
        auditId = audit;
        expectedId = expected;
        this.status = status;
        observedQuantity = observed;
        operationId = op;
        this.actor = actor;
        recordedAt = at;
    }

    public UUID getExpectedId() {
        return expectedId;
    }

    public AuditConsumableStatus getStatus() {
        return status;
    }

    public BigDecimal getObservedQuantity() {
        return observedQuantity;
    }
}

package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_operation")
public class AuditOperation {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "container_audit_id")
    private UUID auditId;

    @Column(name = "client_operation_id")
    private UUID operationId;

    @Column(name = "action")
    private String action;

    @Column(name = "fingerprint")
    private String fingerprint;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    protected AuditOperation() {}

    public AuditOperation(UUID id, UUID org, UUID audit, UUID op, String action, String fingerprint, Instant at) {
        this.id = id;
        organizationId = org;
        auditId = audit;
        operationId = op;
        this.action = action;
        this.fingerprint = fingerprint;
        recordedAt = at;
    }

    public String getAction() {
        return action;
    }

    public String getFingerprint() {
        return fingerprint;
    }
}

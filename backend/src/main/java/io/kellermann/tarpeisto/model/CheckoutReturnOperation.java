package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "checkout_return_operation")
public class CheckoutReturnOperation {
    @Id
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "checkout_manifest_id")
    private UUID manifestId;

    private String fingerprint;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    @Column(name = "recorded_by_user_id")
    private UUID actor;

    protected CheckoutReturnOperation() {}

    public CheckoutReturnOperation(UUID id, UUID org, UUID manifest, String fingerprint, UUID actor, Instant at) {
        this.id = id;
        organizationId = org;
        manifestId = manifest;
        this.fingerprint = fingerprint;
        this.actor = actor;
        recordedAt = at;
    }

    public String getFingerprint() {
        return fingerprint;
    }
}

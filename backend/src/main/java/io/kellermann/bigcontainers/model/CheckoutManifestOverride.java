package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "checkout_manifest_override")
public class CheckoutManifestOverride {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "checkout_manifest_id")
    private UUID manifestId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "recorded_by_user_id")
    private UUID recordedByUserId;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    protected CheckoutManifestOverride() {}

    public CheckoutManifestOverride(UUID id, UUID org, UUID manifest, String reason, UUID actor, Instant at) {
        this.id = id;
        organizationId = org;
        manifestId = manifest;
        this.reason = reason.trim();
        recordedByUserId = actor;
        recordedAt = at;
    }

    public String getReason() {
        return reason;
    }
}

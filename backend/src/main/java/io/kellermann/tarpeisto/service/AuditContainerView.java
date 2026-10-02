package io.kellermann.tarpeisto.service;

import java.util.UUID;

public record AuditContainerView(UUID id, String displayName, String publicCode, boolean sealable) {
    public AuditContainerView(UUID id, String displayName, String publicCode) {
        this(id, displayName, publicCode, false);
    }
}

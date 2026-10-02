package io.kellermann.tarpeisto.controller;

import java.util.UUID;

public record AuditContainerResponse(UUID id, String displayName, String publicCode, boolean sealable) {
    public AuditContainerResponse(UUID id, String displayName, String publicCode) {
        this(id, displayName, publicCode, false);
    }
}

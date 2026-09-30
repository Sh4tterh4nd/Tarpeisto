package io.kellermann.tarpeisto.service;

import java.util.UUID;

public record AuditContainerView(UUID id, String displayName, String publicCode) {}

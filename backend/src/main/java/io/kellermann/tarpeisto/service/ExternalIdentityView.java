package io.kellermann.tarpeisto.service;

import java.time.Instant;
import java.util.UUID;

/**
 * A read projection of one {@link io.kellermann.tarpeisto.model.ExternalIdentity} for the
 * Owner-only external-identity administration endpoints. Deliberately excludes nothing sensitive -
 * an external identity carries no credential - but never exposes a provider access/refresh token
 * (this service never retains one to expose, per ADR-0003).
 */
public record ExternalIdentityView(
        UUID id,
        String issuer,
        String subject,
        String lastEmail,
        String lastDisplayName,
        Instant createdAt,
        Instant lastLoginAt,
        boolean active) {}

package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.ExternalIdentityView;
import java.time.Instant;
import java.util.UUID;

/** Response element for the Owner-only external-identity administration endpoints. */
public record ExternalIdentityResponse(
        UUID id,
        String issuer,
        String subject,
        String lastEmail,
        String lastDisplayName,
        Instant createdAt,
        Instant lastLoginAt,
        boolean active) {

    public static ExternalIdentityResponse from(ExternalIdentityView view) {
        return new ExternalIdentityResponse(
                view.id(),
                view.issuer(),
                view.subject(),
                view.lastEmail(),
                view.lastDisplayName(),
                view.createdAt(),
                view.lastLoginAt(),
                view.active());
    }
}

package io.kellermann.tarpeisto.model;

import java.time.Instant;
import java.util.UUID;

public record TemporaryAccessInvitation(
        UUID id,
        UUID organizationId,
        UUID bookingId,
        UUID auditBatchId,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt) {}

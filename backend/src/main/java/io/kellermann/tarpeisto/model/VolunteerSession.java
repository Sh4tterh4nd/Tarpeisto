package io.kellermann.tarpeisto.model;

import java.time.Instant;
import java.util.UUID;

public record VolunteerSession(
        UUID id,
        UUID organizationId,
        UUID invitationId,
        UUID userId,
        String username,
        String displayName,
        Instant expiresAt,
        UUID bookingId,
        UUID auditBatchId) {}

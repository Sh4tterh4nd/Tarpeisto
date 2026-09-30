package io.kellermann.tarpeisto.security;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Non-secret, immutable grant metadata. The database remains authoritative on every request. */
public record TemporaryAccessContext(
        UUID sessionId, UUID invitationId, UUID bookingId, UUID auditBatchId, Instant expiresAt)
        implements Serializable {}

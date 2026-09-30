package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateTemporaryInvitationRequest(
        UUID bookingId,
        UUID auditBatchId,
        @NotBlank @Size(max = 2048) String joinUrl) {}

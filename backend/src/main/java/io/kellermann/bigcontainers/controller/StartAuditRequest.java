package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotBlank;

public record StartAuditRequest(@NotBlank String containerCode) {}

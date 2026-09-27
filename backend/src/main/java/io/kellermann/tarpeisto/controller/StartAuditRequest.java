package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotBlank;

public record StartAuditRequest(@NotBlank String containerCode) {}

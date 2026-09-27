package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotNull;

public record ArchivePackingRequirementRequest(@NotNull Long expectedVersion, Boolean confirmAffectedBookings) {}

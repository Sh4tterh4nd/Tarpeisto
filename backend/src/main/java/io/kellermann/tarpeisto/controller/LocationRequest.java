package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record LocationRequest(@NotBlank String name, String description, UUID parentLocationId, Long expectedVersion) {}

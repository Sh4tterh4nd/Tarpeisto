package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.Min;

public record ChangeArchiveRequest(boolean archived, @Min(0) long expectedVersion) {}

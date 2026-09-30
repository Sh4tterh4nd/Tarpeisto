package io.kellermann.tarpeisto.service;

import java.util.UUID;

public record ArchiveView(UUID id, boolean archived, long version) {}

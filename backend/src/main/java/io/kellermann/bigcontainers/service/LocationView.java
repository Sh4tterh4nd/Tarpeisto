package io.kellermann.bigcontainers.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LocationView(
        UUID id,
        String name,
        String description,
        UUID parentLocationId,
        boolean archived,
        long version,
        List<String> breadcrumbs,
        String effectivePath,
        Instant createdAt,
        Instant updatedAt) {}

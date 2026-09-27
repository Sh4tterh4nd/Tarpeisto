package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.LocationView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LocationResponse(
        UUID id,
        String name,
        String description,
        UUID parentLocationId,
        boolean archived,
        long version,
        List<String> breadcrumbs,
        String effectivePath,
        Instant createdAt,
        Instant updatedAt) {
    public static LocationResponse from(LocationView view) {
        return new LocationResponse(
                view.id(),
                view.name(),
                view.description(),
                view.parentLocationId(),
                view.archived(),
                view.version(),
                view.breadcrumbs(),
                view.effectivePath(),
                view.createdAt(),
                view.updatedAt());
    }
}

package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.CategoryView;
import java.time.Instant;
import java.util.UUID;

/** Response element for the category catalog endpoints. */
public record CategoryResponse(
        UUID id, String name, String color, boolean archived, Instant createdAt, Instant updatedAt) {

    public static CategoryResponse from(CategoryView view) {
        return new CategoryResponse(
                view.id(), view.name(), view.color(), view.archived(), view.createdAt(), view.updatedAt());
    }
}

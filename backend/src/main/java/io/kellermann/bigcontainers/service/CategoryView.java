package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.Category;
import java.time.Instant;
import java.util.UUID;

/** Read projection of a {@link Category} for the catalog API. */
public record CategoryView(UUID id, String name, String color, boolean archived, Instant createdAt, Instant updatedAt) {

    public static CategoryView from(Category category) {
        return new CategoryView(
                category.getId(),
                category.getName(),
                category.getColor(),
                category.isArchived(),
                category.getCreatedAt(),
                category.getUpdatedAt());
    }
}

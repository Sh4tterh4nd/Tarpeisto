package io.kellermann.bigcontainers.document;

/** Immutable label content detached from persistence before the potentially expensive PDF render begins. */
public record AssetLabelEntry(
        String publicCode,
        String modelName,
        String individualName,
        int unitNumber,
        String categoryName,
        String categoryColor) {}

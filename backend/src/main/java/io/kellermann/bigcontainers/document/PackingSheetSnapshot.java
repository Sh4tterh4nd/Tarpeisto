package io.kellermann.bigcontainers.document;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Immutable direct-content view used by the packing-sheet renderer. */
public record PackingSheetSnapshot(
        String containerName,
        String containerModel,
        String containerCode,
        String categoryColor,
        List<Requirement> requirements,
        List<ChildContainer> childContainers) {

    public PackingSheetSnapshot {
        containerName = Objects.requireNonNull(containerName, "containerName must not be null");
        containerModel = Objects.requireNonNull(containerModel, "containerModel must not be null");
        containerCode = Objects.requireNonNull(containerCode, "containerCode must not be null");
        categoryColor = Objects.requireNonNull(categoryColor, "categoryColor must not be null");
        requirements = List.copyOf(requirements == null ? List.of() : requirements);
        childContainers = List.copyOf(childContainers == null ? List.of() : childContainers);
    }

    public record Requirement(
            Kind kind,
            String modelName,
            BigDecimal quantity,
            String stockUnit,
            String exactAssetCode,
            String exactAssetName) {
        public Requirement {
            kind = Objects.requireNonNull(kind, "kind must not be null");
            modelName = Objects.requireNonNull(modelName, "modelName must not be null");
            quantity = Objects.requireNonNull(quantity, "quantity must not be null");
        }
    }

    public record ChildContainer(String name, String modelName, String publicCode) {
        public ChildContainer {
            name = Objects.requireNonNull(name, "name must not be null");
            modelName = Objects.requireNonNull(modelName, "modelName must not be null");
            publicCode = Objects.requireNonNull(publicCode, "publicCode must not be null");
        }
    }

    public enum Kind {
        SERIALIZED_MODEL,
        CONSUMABLE,
        EXACT
    }
}

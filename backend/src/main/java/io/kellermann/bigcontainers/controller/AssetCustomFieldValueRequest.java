package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One custom field value in a create/set-values request body. Exactly one of {@code stringValue}/
 * {@code dateValue}/{@code optionId} is populated depending on the target field's data type; {@code
 * AssetService} validates the shape against the field's actual data type.
 */
public record AssetCustomFieldValueRequest(
        @NotNull UUID fieldId, String stringValue, LocalDate dateValue, UUID optionId) {}

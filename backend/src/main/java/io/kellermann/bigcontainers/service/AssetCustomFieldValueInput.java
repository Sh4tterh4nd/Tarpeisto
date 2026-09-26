package io.kellermann.bigcontainers.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One caller-supplied custom field value, exactly one of {@code stringValue}/{@code
 * dateValue}/{@code optionId} populated depending on the target field's data type - the same shape
 * {@code AssetCustomFieldValue} itself enforces. Kept as a plain, flat record (mirroring the
 * existing request-DTO style in this codebase) rather than a sealed hierarchy per data type, since
 * the controller layer receives exactly this shape over JSON.
 */
public record AssetCustomFieldValueInput(UUID fieldId, String stringValue, LocalDate dateValue, UUID optionId) {}

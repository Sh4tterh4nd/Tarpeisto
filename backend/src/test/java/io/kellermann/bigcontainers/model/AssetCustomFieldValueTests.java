package io.kellermann.bigcontainers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. Covers the "exactly one value slot is set" shape rule. */
class AssetCustomFieldValueTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void constructorAcceptsAStringValueWithOnlyStringValueSet() {
        AssetCustomFieldValue value = new AssetCustomFieldValue(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CustomFieldDataType.STRING,
                "SN-001",
                null,
                null,
                NOW);
        assertThat(value.getStringValue()).isEqualTo("SN-001");
    }

    @Test
    void constructorRejectsAStringValueWithADateAlsoSet() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssetCustomFieldValue(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        CustomFieldDataType.STRING,
                        "SN-001",
                        LocalDate.of(2025, 1, 1),
                        null,
                        NOW));
    }

    @Test
    void constructorRejectsADateValueWithoutADate() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssetCustomFieldValue(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        CustomFieldDataType.DATE,
                        null,
                        null,
                        null,
                        NOW));
    }

    @Test
    void constructorRejectsADropdownValueWithoutAnOption() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssetCustomFieldValue(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        CustomFieldDataType.DROPDOWN,
                        null,
                        null,
                        null,
                        NOW));
    }

    @Test
    void updateReplacesTheValueInPlace() {
        AssetCustomFieldValue value = new AssetCustomFieldValue(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CustomFieldDataType.STRING,
                "SN-001",
                null,
                null,
                NOW);
        value.update(CustomFieldDataType.STRING, "SN-002", null, null, NOW.plusSeconds(1));
        assertThat(value.getStringValue()).isEqualTo("SN-002");
    }
}

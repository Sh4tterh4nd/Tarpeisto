package io.kellermann.bigcontainers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. */
class ModelCustomFieldTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void constructorRejectsABlankName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelCustomField(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        " ",
                        CustomFieldDataType.STRING,
                        0,
                        NOW));
    }

    @Test
    void isDropdownReflectsDataType() {
        ModelCustomField dropdown = field(CustomFieldDataType.DROPDOWN);
        ModelCustomField string = field(CustomFieldDataType.STRING);
        assertThat(dropdown.isDropdown()).isTrue();
        assertThat(string.isDropdown()).isFalse();
    }

    @Test
    void renameUpdatesNameAndUpdatedAt() {
        ModelCustomField field = field(CustomFieldDataType.STRING);
        field.rename("MAC Address", NOW.plusSeconds(1));
        assertThat(field.getName()).isEqualTo("MAC Address");
        assertThat(field.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void changeDataTypeUpdatesDataType() {
        ModelCustomField field = field(CustomFieldDataType.STRING);
        field.changeDataType(CustomFieldDataType.DATE, NOW.plusSeconds(1));
        assertThat(field.getDataType()).isEqualTo(CustomFieldDataType.DATE);
    }

    @Test
    void reorderUpdatesDisplayOrder() {
        ModelCustomField field = field(CustomFieldDataType.STRING);
        field.reorder(5, NOW.plusSeconds(1));
        assertThat(field.getDisplayOrder()).isEqualTo(5);
    }

    @Test
    void archiveAndRestoreToggleArchivedState() {
        ModelCustomField field = field(CustomFieldDataType.STRING);
        field.archive(NOW.plusSeconds(1));
        assertThat(field.isArchived()).isTrue();
        field.restore(NOW.plusSeconds(2));
        assertThat(field.isArchived()).isFalse();
    }

    private static ModelCustomField field(CustomFieldDataType dataType) {
        return new ModelCustomField(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Serial Number", dataType, 0, NOW);
    }
}

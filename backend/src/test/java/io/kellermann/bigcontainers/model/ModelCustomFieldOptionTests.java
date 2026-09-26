package io.kellermann.bigcontainers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. */
class ModelCustomFieldOptionTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void constructorRejectsABlankValue() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelCustomFieldOption(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), " ", 0, NOW));
    }

    @Test
    void renameUpdatesValue() {
        ModelCustomFieldOption option = option();
        option.rename("2.4GHz", NOW.plusSeconds(1));
        assertThat(option.getValue()).isEqualTo("2.4GHz");
    }

    @Test
    void reorderUpdatesDisplayOrder() {
        ModelCustomFieldOption option = option();
        option.reorder(3, NOW.plusSeconds(1));
        assertThat(option.getDisplayOrder()).isEqualTo(3);
    }

    @Test
    void archiveAndRestoreToggleArchivedState() {
        ModelCustomFieldOption option = option();
        option.archive(NOW.plusSeconds(1));
        assertThat(option.isArchived()).isTrue();
        option.restore(NOW.plusSeconds(2));
        assertThat(option.isArchived()).isFalse();
    }

    private static ModelCustomFieldOption option() {
        return new ModelCustomFieldOption(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "5GHz", 0, NOW);
    }
}

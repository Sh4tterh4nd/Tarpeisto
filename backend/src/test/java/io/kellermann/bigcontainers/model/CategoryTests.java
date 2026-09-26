package io.kellermann.bigcontainers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. */
class CategoryTests {

    @Test
    void normalizeColorUppercasesAndAddsALeadingHash() {
        assertThat(Category.normalizeColor("ff00aa")).isEqualTo("#FF00AA");
        assertThat(Category.normalizeColor("#ff00aa")).isEqualTo("#FF00AA");
        assertThat(Category.normalizeColor(" #FF00AA ")).isEqualTo("#FF00AA");
    }

    @Test
    void normalizeColorRejectsAnInvalidValue() {
        assertThatIllegalArgumentException().isThrownBy(() -> Category.normalizeColor("not-a-color"));
        assertThatIllegalArgumentException().isThrownBy(() -> Category.normalizeColor("#FFF"));
        assertThatIllegalArgumentException().isThrownBy(() -> Category.normalizeColor("#GGGGGG"));
    }

    @Test
    void normalizeColorRejectsNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> Category.normalizeColor(null));
    }

    @Test
    void constructorRejectsABlankName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Category(UUID.randomUUID(), UUID.randomUUID(), " ", "#FF00AA", Instant.now()));
    }

    @Test
    void renameUpdatesNameAndColorAndUpdatedAt() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Category category = new Category(UUID.randomUUID(), UUID.randomUUID(), "Networking", "#FF00AA", createdAt);

        Instant renamedAt = Instant.parse("2026-02-01T00:00:00Z");
        category.rename("Cables", "00ff00", renamedAt);

        assertThat(category.getName()).isEqualTo("Cables");
        assertThat(category.getColor()).isEqualTo("#00FF00");
        assertThat(category.getUpdatedAt()).isEqualTo(renamedAt);
        assertThat(category.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void archiveAndRestoreToggleArchivedState() {
        Category category = new Category(UUID.randomUUID(), UUID.randomUUID(), "Networking", "#FF00AA", Instant.now());
        assertThat(category.isArchived()).isFalse();

        Instant archivedAt = Instant.parse("2026-03-01T00:00:00Z");
        category.archive(archivedAt);
        assertThat(category.isArchived()).isTrue();
        assertThat(category.getArchivedAt()).isEqualTo(archivedAt);

        category.restore(Instant.parse("2026-04-01T00:00:00Z"));
        assertThat(category.isArchived()).isFalse();
        assertThat(category.getArchivedAt()).isNull();
    }
}

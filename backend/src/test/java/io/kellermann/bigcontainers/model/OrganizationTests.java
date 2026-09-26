package io.kellermann.bigcontainers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. */
class OrganizationTests {

    @Test
    void constructorRejectsABlankName() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Organization(UUID.randomUUID(), "  ", Instant.now()));
    }

    @Test
    void constructorRejectsANullName() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Organization(UUID.randomUUID(), null, Instant.now()));
    }

    @Test
    void renameUpdatesNameAndUpdatedAtButNotCreatedAt() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Organization organization = new Organization(UUID.randomUUID(), "Original Name", createdAt);

        Instant renamedAt = Instant.parse("2026-02-01T00:00:00Z");
        organization.rename("New Name", renamedAt);

        assertThat(organization.getName()).isEqualTo("New Name");
        assertThat(organization.getUpdatedAt()).isEqualTo(renamedAt);
        assertThat(organization.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void renameRejectsABlankName() {
        Organization organization = new Organization(UUID.randomUUID(), "Original Name", Instant.now());

        assertThatIllegalArgumentException().isThrownBy(() -> organization.rename(" ", Instant.now()));
    }
}

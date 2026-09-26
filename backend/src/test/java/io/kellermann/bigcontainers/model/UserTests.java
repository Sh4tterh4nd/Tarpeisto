package io.kellermann.bigcontainers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. */
class UserTests {

    @Test
    void constructorRejectsABlankUsername() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new User(UUID.randomUUID(), " ", null, "Display Name", "hash", true, Instant.now()));
    }

    @Test
    void constructorRejectsABlankDisplayName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new User(UUID.randomUUID(), "username", null, " ", "hash", true, Instant.now()));
    }

    @Test
    void constructorAllowsANullEmailAndNullPasswordHash() {
        User user = new User(UUID.randomUUID(), "username", null, "Display Name", null, true, Instant.now());

        assertThat(user.getEmail()).isNull();
        assertThat(user.getPasswordHash()).isNull();
    }

    @Test
    void disableClearsEnabledAndUpdatesTimestamp() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        User user = new User(UUID.randomUUID(), "username", null, "Display Name", "hash", true, createdAt);

        Instant disabledAt = Instant.parse("2026-02-01T00:00:00Z");
        user.disable(disabledAt);

        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getUpdatedAt()).isEqualTo(disabledAt);
    }

    @Test
    void enableAfterDisableRestoresEnabled() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        User user = new User(UUID.randomUUID(), "username", null, "Display Name", "hash", true, createdAt);
        user.disable(Instant.parse("2026-02-01T00:00:00Z"));

        user.enable(Instant.parse("2026-03-01T00:00:00Z"));

        assertThat(user.isEnabled()).isTrue();
    }
}

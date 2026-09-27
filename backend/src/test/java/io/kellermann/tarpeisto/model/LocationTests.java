package io.kellermann.tarpeisto.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocationTests {
    @Test
    void trims_name_and_description_and_tracks_archive_state() {
        Location location =
                new Location(UUID.randomUUID(), UUID.randomUUID(), "  Shelf A  ", "  top row ", null, Instant.EPOCH);
        assertThat(location.getName()).isEqualTo("Shelf A");
        assertThat(location.getDescription()).isEqualTo("top row");
        location.archive(Instant.EPOCH.plusSeconds(1));
        assertThat(location.isArchived()).isTrue();
        location.restore(Instant.EPOCH.plusSeconds(2));
        assertThat(location.isArchived()).isFalse();
    }

    @Test
    void rejects_blank_names() {
        assertThatThrownBy(() -> new Location(UUID.randomUUID(), UUID.randomUUID(), " ", null, null, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A location name is required.");
    }
}

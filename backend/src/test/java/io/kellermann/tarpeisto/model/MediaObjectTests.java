package io.kellermann.tarpeisto.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaObjectTests {
    @Test
    void layoutImagesRetainCaptionAndCanMove() {
        var media = media(MediaPurpose.CONTAINER_LAYOUT, "Bottom layer", 1);

        media.updateLayout("Top tray", 0, Instant.parse("2026-09-26T11:00:00Z"));

        assertThat(media.getCaption()).isEqualTo("Top tray");
        assertThat(media.getDisplayOrder()).isZero();
    }

    @Test
    void referenceImagesCannotBeGivenLayoutMetadata() {
        var media = media(MediaPurpose.ASSET_REFERENCE, null, 0);

        assertThatThrownBy(() -> media.updateLayout("Top tray", 0, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("layout");
    }

    private static MediaObject media(MediaPurpose purpose, String caption, int order) {
        var now = Instant.parse("2026-09-26T10:00:00Z");
        return new MediaObject(
                UUID.randomUUID(),
                UUID.randomUUID(),
                purpose == MediaPurpose.MODEL_REFERENCE ? UUID.randomUUID() : null,
                purpose == MediaPurpose.MODEL_REFERENCE ? null : UUID.randomUUID(),
                purpose,
                "organizations/one/media/original.jpg",
                "organizations/one/media/thumbnail.jpg",
                "image/jpeg",
                42,
                "0".repeat(64),
                caption,
                order,
                false,
                UUID.randomUUID(),
                now);
    }
}

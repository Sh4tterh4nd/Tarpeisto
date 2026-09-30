package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.ArchiveView;
import java.util.UUID;

public record ArchiveResponse(UUID id, boolean archived, long version) {
    public static ArchiveResponse from(ArchiveView view) {
        return new ArchiveResponse(view.id(), view.archived(), view.version());
    }
}

package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.PackingTemplateView;
import java.util.List;
import java.util.UUID;

public record PackingTemplateResponse(
        UUID id,
        String name,
        String description,
        boolean archived,
        long version,
        List<PackingRequirementResponse> requirements) {
    public static PackingTemplateResponse from(PackingTemplateView v) {
        return new PackingTemplateResponse(
                v.id(),
                v.name(),
                v.description(),
                v.archived(),
                v.version(),
                v.requirements().stream().map(PackingRequirementResponse::from).toList());
    }
}

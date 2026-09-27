package io.kellermann.tarpeisto.service;

import java.util.List;
import java.util.UUID;

public record PackingTemplateView(
        UUID id,
        String name,
        String description,
        boolean archived,
        long version,
        List<PackingRequirementView> requirements) {}

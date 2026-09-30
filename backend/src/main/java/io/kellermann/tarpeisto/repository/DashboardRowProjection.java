package io.kellermann.tarpeisto.repository;

import java.util.UUID;

public record DashboardRowProjection(UUID id, String label, String code, String state, String reason, UUID targetId) {}

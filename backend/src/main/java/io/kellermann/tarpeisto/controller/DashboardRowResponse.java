package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.DashboardService;
import java.util.UUID;

public record DashboardRowResponse(
        UUID id,
        String label,
        String code,
        String state,
        String reason,
        String actionLabel,
        String actionPath,
        boolean readOnly) {
    static DashboardRowResponse from(DashboardService.Row r) {
        return new DashboardRowResponse(
                r.id(), r.label(), r.code(), r.state(), r.reason(), r.actionLabel(), r.actionPath(), r.readOnly());
    }
}

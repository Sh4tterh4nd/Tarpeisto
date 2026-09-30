package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.DashboardService;
import java.util.List;

public record DashboardResponse(List<DashboardQueueResponse> queues) {
    static DashboardResponse from(DashboardService.View v) {
        return new DashboardResponse(
                v.queues().stream().map(DashboardQueueResponse::from).toList());
    }
}

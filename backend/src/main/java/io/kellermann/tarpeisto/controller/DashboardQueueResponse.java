package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.DashboardQueue;
import io.kellermann.tarpeisto.service.DashboardService;
import java.util.List;

public record DashboardQueueResponse(
        DashboardQueue queue, long count, List<DashboardRowResponse> items, String nextCursor) {
    static DashboardQueueResponse from(DashboardService.Page p) {
        return new DashboardQueueResponse(
                p.queue(),
                p.count(),
                p.items().stream().map(DashboardRowResponse::from).toList(),
                p.nextCursor());
    }
}

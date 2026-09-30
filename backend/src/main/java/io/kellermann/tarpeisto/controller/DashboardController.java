package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.DashboardQueue;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.DashboardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {
    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/dashboard")
    public DashboardResponse get(@AuthenticationPrincipal TarpeistoPrincipal p) {
        return DashboardResponse.from(dashboard.get(p));
    }

    @GetMapping("/api/v1/dashboard/{queue}")
    public DashboardQueueResponse page(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable DashboardQueue queue,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String cursor) {
        return DashboardQueueResponse.from(dashboard.page(p, queue, limit, cursor));
    }
}

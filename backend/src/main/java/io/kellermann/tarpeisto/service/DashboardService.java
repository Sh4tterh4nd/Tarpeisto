package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.DashboardQueue;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.repository.DashboardRowProjection;
import io.kellermann.tarpeisto.repository.JdbcDashboardRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
    public record Row(
            UUID id,
            String label,
            String code,
            String state,
            String reason,
            String actionLabel,
            String actionPath,
            boolean readOnly) {}

    public record Page(DashboardQueue queue, long count, List<Row> items, String nextCursor) {}

    public record View(List<Page> queues) {}

    private final JdbcDashboardRepository repository;
    private final PackingRequirementService packing;
    private final BookingReservationService reservations;
    private final Clock clock;

    public DashboardService(
            JdbcDashboardRepository repository,
            PackingRequirementService packing,
            BookingReservationService reservations,
            Clock clock) {
        this.repository = repository;
        this.packing = packing;
        this.reservations = reservations;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View get(TarpeistoPrincipal p) {
        require(p, 10);
        return new View(java.util.Arrays.stream(DashboardQueue.values())
                .map(q -> page(p, q, 10, null))
                .toList());
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page page(TarpeistoPrincipal p, DashboardQueue queue, int limit, String cursor) {
        require(p, limit);
        String filters = p.organizationId() + ":" + queue;
        var anchor = InventorySearchCursor.parse(cursor, filters);
        UUID after = anchor == null ? null : anchor.id();
        var now = clock.instant();
        List<Row> rows = new ArrayList<>();
        long count;
        if (queue == DashboardQueue.CONTAINERS) {
            count = 0;
            UUID candidateCursor = null;
            while (true) {
                var candidates = repository.page(p.organizationId(), queue, now, 100, candidateCursor);
                for (var r : candidates) {
                    var preview = packing.preview(p, r.id(), Map.of());
                    var availability = reservations.previewContainer(p, r.id());
                    if (preview.complete() && availability.reservable()) continue;
                    count++;
                    if ((after == null || r.id().toString().compareTo(after.toString()) > 0)
                            && rows.size() < limit + 1) {
                        String reason = !preview.complete()
                                ? "Packing requirements are incomplete"
                                : availability.conflicts().stream()
                                        .map(BookingConflictView::message)
                                        .distinct()
                                        .collect(java.util.stream.Collectors.joining("; "));
                        rows.add(new Row(
                                r.id(),
                                r.label(),
                                r.code(),
                                !preview.complete() ? "INCOMPLETE" : "UNAVAILABLE",
                                reason,
                                "View packing",
                                "/inventory/assets/" + r.id(),
                                p.role() == OrganizationRole.VIEWER));
                    }
                }
                if (candidates.size() < 100) break;
                candidateCursor = candidates.getLast().id();
            }
        } else {
            count = repository.count(p.organizationId(), queue, now);
            rows.addAll(repository.page(p.organizationId(), queue, now, limit + 1, after).stream()
                    .map(r -> row(p, queue, r))
                    .toList());
        }
        String next = rows.size() > limit
                ? InventorySearchCursor.encode(rows.get(limit - 1).id(), "", filters)
                : null;
        return new Page(queue, count, rows.stream().limit(limit).toList(), next);
    }

    private static Row row(TarpeistoPrincipal p, DashboardQueue q, DashboardRowProjection r) {
        boolean reviewer = p.role() == OrganizationRole.OWNER || p.role() == OrganizationRole.DEPUTY;
        boolean viewer = p.role() == OrganizationRole.VIEWER;
        String path = switch (q) {
            case UPCOMING_EVENTS, OUTSTANDING_CUSTODY -> "/events/" + r.targetId();
            case AUDITS -> viewer ? "/inventory/assets/" + r.targetId() : "/audits/tasks/" + r.id();
            case REVIEW ->
                reviewer ? "/review" : viewer ? "/inventory/assets?query=" + r.code() : "/audits/tasks/" + r.targetId();
            case LOW_STOCK -> "/inventory/models/" + r.targetId();
            default -> "/inventory/assets/" + r.targetId();
        };
        String action = switch (q) {
            case UPCOMING_EVENTS -> viewer ? "View event" : "Prepare checkout";
            case OUTSTANDING_CUSTODY -> viewer ? "View event" : "Check in";
            case AUDITS -> viewer ? "View container" : "IN_PROGRESS".equals(r.state()) ? "Resume audit" : "Open audit";
            case REVIEW -> reviewer ? "Review finding" : "View audit";
            case REPAIRS -> "View repair";
            case METADATA -> viewer ? "View asset" : "Complete metadata";
            case LOW_STOCK -> "View stock";
            default -> "View packing";
        };
        return new Row(
                r.id(),
                r.label(),
                r.code(),
                r.state(),
                r.reason(),
                action,
                path,
                viewer || q == DashboardQueue.REVIEW && !reviewer);
    }

    private static void require(TarpeistoPrincipal p, int limit) {
        if (p == null) throw new AccessDeniedException("Authentication required.");
        p.requirePermanent();
        if (limit < 1 || limit > 100) throw new ValidationFailedException("limit must be between 1 and 100.");
    }
}

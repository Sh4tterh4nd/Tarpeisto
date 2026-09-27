package io.kellermann.tarpeisto.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Peak simultaneous demand on a half-open interval. Ending holds are released before adjacent starts. */
@Component
public class BookingCapacityCalculator {
    public BigDecimal peak(Instant start, Instant end, BigDecimal requested, List<DemandWindow> existing) {
        Map<Instant, BigDecimal> changes = new TreeMap<>();
        for (DemandWindow w : existing) {
            Instant from = w.start().isAfter(start) ? w.start() : start,
                    until = w.end().isBefore(end) ? w.end() : end;
            if (from.isBefore(until)) {
                changes.merge(from, w.quantity(), BigDecimal::add);
                changes.merge(until, w.quantity().negate(), BigDecimal::add);
            }
        }
        BigDecimal current = requested, peak = requested;
        for (Map.Entry<Instant, BigDecimal> change : changes.entrySet()) {
            if (!change.getKey().isBefore(end)) break;
            current = current.add(change.getValue());
            peak = peak.max(current);
        }
        return peak;
    }

    public record DemandWindow(Instant start, Instant end, BigDecimal quantity) {}
}

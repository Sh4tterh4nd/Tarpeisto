package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingCapacityCalculatorTests {
    private final BookingCapacityCalculator calculator = new BookingCapacityCalculator();

    @Test
    void sumsSimultaneousDemandAndReleasesAdjacentHolds() {
        Instant start = Instant.parse("2027-01-01T08:00:00Z"),
                middle = start.plusSeconds(3600),
                end = middle.plusSeconds(3600);
        assertThat(calculator.peak(
                        start,
                        end,
                        BigDecimal.ONE,
                        List.of(
                                new BookingCapacityCalculator.DemandWindow(start, middle, BigDecimal.ONE),
                                new BookingCapacityCalculator.DemandWindow(middle, end, BigDecimal.ONE))))
                .isEqualByComparingTo("2");
        assertThat(calculator.peak(
                        start,
                        end,
                        BigDecimal.ONE,
                        List.of(
                                new BookingCapacityCalculator.DemandWindow(start, end, BigDecimal.ONE),
                                new BookingCapacityCalculator.DemandWindow(middle, end, BigDecimal.ONE))))
                .isEqualByComparingTo("3");
    }

    @Test
    void clipsOutsideIntervalsAndIgnoresExactlyAdjacentWindows() {
        Instant start = Instant.parse("2027-01-01T08:00:00Z"), end = start.plusSeconds(3600);
        assertThat(calculator.peak(
                        start,
                        end,
                        BigDecimal.ONE,
                        List.of(
                                new BookingCapacityCalculator.DemandWindow(
                                        start.minusSeconds(3600), start, new BigDecimal("10")),
                                new BookingCapacityCalculator.DemandWindow(
                                        end, end.plusSeconds(3600), new BigDecimal("10")))))
                .isEqualByComparingTo("1");
    }
}

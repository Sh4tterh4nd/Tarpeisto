package io.kellermann.bigcontainers.config;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the single {@link Clock} bean that time-sensitive business logic must depend on
 * instead of calling the system clock directly, per the development policies.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    /**
     * The clock ticks in whole microseconds rather than nanoseconds.
     *
     * <p>{@link java.time.Instant} holds nanosecond precision, but the development policies
     * require instants to be stored in PostgreSQL {@code timestamptz} columns, which hold only
     * microseconds. A nanosecond-precision value is therefore silently rounded on write, so an
     * entity still held in memory stops being equal to the same row read back from the database.
     * That mismatch is invisible until something compares the two, and it would apply to every
     * entity with an audit timestamp.
     *
     * <p>Truncating here means every business timestamp derived from this clock survives a
     * database round trip unchanged, instead of each comparison having to remember to truncate.
     */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), ChronoUnit.MICROS.getDuration());
    }
}

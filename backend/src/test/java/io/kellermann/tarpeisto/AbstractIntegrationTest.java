package io.kellermann.tarpeisto;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared base for every backend integration test that needs a real PostgreSQL database
 * (PostgreSQL 18.6, matching the pinned baseline in ADR-0001), never H2 or another in-memory
 * substitute, per docs/DEVELOPMENT_POLICIES.md section 5.2/7. The container is provided to the
 * Spring context through {@code @ServiceConnection}, which wires {@code spring.datasource.*}
 * automatically - no datasource properties are set by hand.
 *
 * <p>{@code @AutoConfigureTestRestTemplate} is required here: in Spring Boot 4,
 * {@code TestRestTemplate} moved into its own module and its bean is only registered when this
 * annotation is present (it is no longer implied by {@code @SpringBootTest(webEnvironment =
 * RANDOM_PORT)} alone). It is placed on this shared base, not on individual test classes, so
 * every subclass merges to the exact same context configuration and keeps sharing one cached
 * Spring context.
 *
 * <p>This class deliberately does <strong>not</strong> use {@code @Testcontainers} with
 * {@code @Container}. That JUnit extension manages a static container per test class: it starts
 * the container before the first test class and <em>stops</em> it when that class finishes, so
 * every later subclass of this base would receive an already-stopped container and fail with
 * "Connection refused". Because the failing class depends on execution order, the symptom moves
 * between runs, which makes it look like flaky infrastructure rather than a lifecycle bug.
 *
 * <p>Instead this uses the documented singleton-container pattern: one container is started once
 * per JVM in a static initializer and is never stopped explicitly. Testcontainers' Ryuk sidecar
 * reaps it when the JVM exits, so nothing is leaked - which matters here because integration
 * tests may run against a shared remote Docker host.
 */
// Ordinary integration fixtures share one loopback client across hundreds of unrelated logins.
// Dedicated security admission tests use isolated, deliberately low budgets.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tarpeisto.security.login-rate-limit.max-client-attempts=10000")
@AutoConfigureTestRestTemplate
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    static {
        POSTGRES.start();
    }
}

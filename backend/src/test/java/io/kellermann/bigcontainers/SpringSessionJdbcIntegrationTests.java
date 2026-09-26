package io.kellermann.bigcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Regression test for a Phase 0 defect: {@code spring.session.store-type: jdbc} in {@code
 * application.yml} is inert unless the Boot 4 {@code spring-boot-starter-session-jdbc}
 * autoconfiguration module - not just {@code org.springframework.session:spring-session-jdbc}
 * itself - is on the classpath. Without it, no Spring Session {@link SessionRepository} bean is
 * registered at all (plain Servlet-container sessions are used instead), which silently violates
 * ADR-0001's requirement that sessions survive an application restart or a second instance.
 *
 * <p>This does not just prove the context starts (as {@link MigrationIntegrationTests} does for
 * Flyway/Hibernate); it proves the session repository is genuinely the JDBC-backed
 * implementation and that a session it saves actually lands as a row in {@code SPRING_SESSION}.
 */
class SpringSessionJdbcIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void sessionRepositoryIsTheJdbcImplementationNotAnInMemoryFallback() {
        assertThat(sessionRepository).isInstanceOf(JdbcIndexedSessionRepository.class);
    }

    @Test
    void aSavedSessionIsPersistedAsARowInTheSpringSessionTable() {
        String sessionId = createSaveAndReturnSessionId(sessionRepository);

        try {
            Integer matchingRows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM spring_session WHERE session_id = ?", Integer.class, sessionId);

            assertThat(matchingRows).isEqualTo(1);
        } finally {
            sessionRepository.deleteById(sessionId);
        }
    }

    // A generic helper method binds the wildcard-typed field's element type to a single fixed
    // type parameter S for the whole call, which createSession()+save() otherwise cannot do
    // across two separate accesses of a SessionRepository<? extends Session> field.
    private static <S extends Session> String createSaveAndReturnSessionId(SessionRepository<S> repository) {
        S session = repository.createSession();
        session.setAttribute("probe", "phase-0-regression-test");
        repository.save(session);
        return session.getId();
    }
}

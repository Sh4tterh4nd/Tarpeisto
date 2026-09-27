package io.kellermann.tarpeisto.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL coordination used to serialize the installation's one-time setup transaction. */
@Repository
public class InitialSetupRepository {

    private static final long INITIAL_SETUP_LOCK_ID = 8_402_600_001L;

    private final JdbcClient jdbcClient;

    public InitialSetupRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Holds a transaction-scoped advisory lock until the surrounding setup transaction ends. */
    public void lock() {
        jdbcClient
                .sql("SELECT pg_advisory_xact_lock(:lockId)")
                .param("lockId", INITIAL_SETUP_LOCK_ID)
                .query((resultSet, rowNumber) -> true)
                .single();
    }
}

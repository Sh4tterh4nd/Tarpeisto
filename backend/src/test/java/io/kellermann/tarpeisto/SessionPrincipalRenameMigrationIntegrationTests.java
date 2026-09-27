package io.kellermann.tarpeisto;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Proves V16 invalidates serialized V15 sessions and cascades their attributes. */
class SessionPrincipalRenameMigrationIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void invalidatesV15SessionsAndCascadesTheirAttributesWhenMigratingToV16() throws Exception {
        String schema =
                "session_principal_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target("15")
                .load()
                .migrate();
        try (var connection = dataSource.getConnection();
                var sql = connection.createStatement()) {
            sql.execute("SET search_path TO " + schema);
            String primaryId = UUID.randomUUID().toString();
            sql.execute("INSERT INTO spring_session (primary_id,session_id,creation_time,last_access_time,"
                    + "max_inactive_interval,expiry_time,principal_name) VALUES ('"
                    + primaryId
                    + "','"
                    + UUID.randomUUID()
                    + "',1,1,1800,1801,'migration-test')");
            sql.execute("INSERT INTO spring_session_attributes (session_primary_id,attribute_name,attribute_bytes) "
                    + "VALUES ('"
                    + primaryId
                    + "','SPRING_SECURITY_CONTEXT','\\x00')");
            assertThat(countRows(sql, "spring_session")).isEqualTo(1);
            assertThat(countRows(sql, "spring_session_attributes")).isEqualTo(1);
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load()
                    .migrate();
            assertThat(countRows(sql, "spring_session")).isZero();
            assertThat(countRows(sql, "spring_session_attributes")).isZero();
            sql.execute("SET search_path TO public");
        }
    }

    private static int countRows(java.sql.Statement sql, String table) throws SQLException {
        try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }
}

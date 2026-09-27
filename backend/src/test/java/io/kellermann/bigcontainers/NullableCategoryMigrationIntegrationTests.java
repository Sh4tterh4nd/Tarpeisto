package io.kellermann.bigcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Upgrade rehearsal with existing V14 categorized models, isolated in a fresh test schema. */
class NullableCategoryMigrationIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void upgradesExistingCategorizedV14ModelsWithoutRemovingTenantForeignKey() throws Exception {
        String schema = "qol_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target("14")
                .load()
                .migrate();
        try (var connection = dataSource.getConnection();
                var sql = connection.createStatement()) {
            sql.execute("SET search_path TO " + schema);
            UUID org = UUID.randomUUID();
            UUID foreign = UUID.randomUUID();
            UUID category = UUID.randomUUID();
            UUID model = UUID.randomUUID();
            sql.execute("INSERT INTO organization (id,name,created_at,updated_at) VALUES ('" + org
                    + "','Own',now(),now()), ('" + foreign + "','Foreign',now(),now())");
            sql.execute("INSERT INTO category (id,organization_id,name,color,created_at,updated_at) VALUES ('"
                    + category + "','" + org + "','Existing','#112233',now(),now())");
            sql.execute(
                    "INSERT INTO asset_model (id,organization_id,name,category_id,tracking_mode,can_contain_assets,created_at,updated_at) VALUES ('"
                            + model + "','" + org + "','Existing','" + category
                            + "','SERIALIZED_ASSET',false,now(),now())");
            assertThatThrownBy(
                            () -> sql.execute("UPDATE asset_model SET category_id = NULL WHERE id = '" + model + "'"))
                    .isInstanceOf(SQLException.class);
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load()
                    .migrate();
            try (var row = sql.executeQuery("SELECT category_id FROM asset_model WHERE id = '" + model + "'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getObject(1, UUID.class)).isEqualTo(category);
            }
            sql.execute("UPDATE asset_model SET category_id = NULL WHERE id = '" + model + "'");
            assertThatThrownBy(() -> sql.execute(
                            "INSERT INTO asset_model (id,organization_id,name,category_id,tracking_mode,can_contain_assets,created_at,updated_at) VALUES ('"
                                    + UUID.randomUUID() + "','" + foreign + "','Invalid','" + category
                                    + "','SERIALIZED_ASSET',false,now(),now())"))
                    .isInstanceOf(SQLException.class);
            sql.execute("UPDATE asset_model SET category_id = '" + category + "' WHERE id = '" + model + "'");
            assertThatThrownBy(() -> sql.execute("DELETE FROM category WHERE id = '" + category + "'"))
                    .isInstanceOf(SQLException.class);
            sql.execute("SET search_path TO public");
        }
    }
}

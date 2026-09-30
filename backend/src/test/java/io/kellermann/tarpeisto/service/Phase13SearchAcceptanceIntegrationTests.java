package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.ArchiveKind;
import io.kellermann.tarpeisto.model.AssetSearchFilter;
import io.kellermann.tarpeisto.model.Condition;
import io.kellermann.tarpeisto.model.LifecycleState;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

class Phase13SearchAcceptanceIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private AssetModelService models;

    @Autowired
    private AssetService assets;

    @Autowired
    private CatalogSearchService catalog;

    @Autowired
    private ConsumableStockService stocks;

    @Autowired
    private ArchiveService archives;

    @Autowired
    private AuditService audits;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    @Transactional
    void cursorCarriesOriginalDatabaseAnchorAndRejectsDifferentQueriesAndOrganizations() {
        var owner = owner();
        var model = model(owner, "Anchor units", false, TrackingMode.SERIALIZED_ASSET);
        for (String name : List.of("Alpha", "Bravo", "Charlie", "Delta"))
            assets.create(owner, model.id(), name, null, List.of());
        var first = search(owner, null, false, false, 2, null, null);
        var expected =
                search(owner, null, false, false, 2, first.nextCursor(), null).items();
        assets.rename(owner, first.items().getLast().id(), "AAA renamed anchor");
        entityManager.flush();
        assertThat(search(owner, null, false, false, 2, first.nextCursor(), null)
                        .items())
                .isEqualTo(expected);
        assertThatThrownBy(() -> search(owner, "null", false, false, 2, first.nextCursor(), null))
                .isInstanceOf(ValidationFailedException.class);
        var other = owner();
        assertThatThrownBy(() -> search(other, null, false, false, 2, first.nextCursor(), null))
                .isInstanceOf(ValidationFailedException.class);
        model(owner, "İstanbul", false, TrackingMode.SERIALIZED_ASSET);
        model(owner, "Äquipment", false, TrackingMode.SERIALIZED_ASSET);
        entityManager.flush();
        var modelPage = catalog.models(owner, null, null, null, false, 1, null);
        assertThatThrownBy(() -> catalog.models(owner, "null", null, null, false, 1, modelPage.nextCursor()))
                .isInstanceOf(ValidationFailedException.class);
        List<UUID> ids = new ArrayList<>();
        String cursor = null;
        do {
            var page = catalog.models(owner, null, null, null, false, 1, cursor);
            ids.addAll(page.items().stream()
                    .map(CatalogSearchService.ModelView::id)
                    .toList());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(ids).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    @Transactional
    void archivedOnlyBalanceStillShowsModelWithZeroOnHandAndExplicitArchiveFilterFindsHistory() {
        var owner = owner();
        var cases = model(owner, "Cases", true, TrackingMode.SERIALIZED_ASSET);
        var box = assets.create(owner, cases.id(), "Stock case", null, List.of());
        var stockModel = model(owner, "Archived stock model", false, TrackingMode.QUANTITY_STOCK);
        stocks.receive(owner, stockModel.id(), box.id(), BigDecimal.ONE, "Receipt");
        var empty = stocks.consume(owner, stockModel.id(), box.id(), BigDecimal.ONE, "Used");
        archives.change(owner, ArchiveKind.STOCK, empty.id(), true, empty.version());
        var normal =
                catalog.stocks(owner, null, null, null, null, false, true, BigDecimal.ZERO, BigDecimal.ZERO, 10, null);
        assertThat(normal.items()).singleElement().satisfies(row -> {
            assertThat(row.assetModelId()).isEqualTo(stockModel.id());
            assertThat(row.id()).isNull();
            assertThat(row.totalOnHand()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row.archived()).isFalse();
        });
        var history = catalog.stocks(owner, null, null, null, null, true, null, null, null, 10, null);
        assertThat(history.items()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(empty.id());
            assertThat(row.archived()).isTrue();
        });
    }

    @Test
    @Transactional
    void normalSearchSeparatesArchivesFromInactiveAndDamageFromAvailability() {
        var owner = owner();
        var model = model(owner, "Lifecycle units", false, TrackingMode.SERIALIZED_ASSET);
        var active = assets.create(owner, model.id(), "Active", null, List.of());
        var archived = assets.create(owner, model.id(), "Archived", null, List.of());
        var inactive = assets.create(owner, model.id(), "Lost", null, List.of());
        assets.archive(owner, archived.id());
        assets.changeLifecycleState(owner, inactive.id(), LifecycleState.LOST, "Acceptance loss");
        assets.changeCondition(owner, active.id(), Condition.DAMAGED, "Condition is separate from availability");
        entityManager.flush();
        assertThat(search(owner, null, false, false, 10, null, null).items())
                .extracting(AssetSearchView::id)
                .containsExactly(active.id());
        assertThat(search(owner, null, true, false, 10, null, null).items())
                .extracting(AssetSearchView::id)
                .containsExactlyInAnyOrder(active.id(), inactive.id());
        assertThat(search(owner, null, false, true, 10, null, null).items())
                .extracting(AssetSearchView::id)
                .containsExactlyInAnyOrder(active.id(), archived.id());
        var available = new AssetSearchFilter(false, null, null, null, null, null, null, null, null, "AVAILABLE");
        assertThat(assets.search(owner, null, null, null, false, "name", "asc", 10, null, available)
                        .items())
                .extracting(AssetSearchView::id)
                .containsExactly(active.id());
    }

    @Test
    @Transactional
    void operationalAuditFilterDoesNotMatchAnOlderCompletedTask() {
        var owner = owner();
        var cases = model(owner, "Audited cases", true, TrackingMode.SERIALIZED_ASSET);
        var box = assets.create(owner, cases.id(), "Repeat audit case", null, List.of());
        var first = audits.launchContainerAudit(owner, box.id(), box.publicCode(), UUID.randomUUID());
        audits.complete(owner, first.id(), UUID.randomUUID(), box.publicCode(), false, false);
        audits.launchContainerAudit(owner, box.id(), box.publicCode(), UUID.randomUUID());
        entityManager.flush();
        assertThat(search(owner, null, false, false, 10, null, "COMPLETED").items())
                .isEmpty();
        assertThat(search(owner, null, false, false, 10, null, "IN_PROGRESS").items())
                .extracting(AssetSearchView::id)
                .containsExactly(box.id());
    }

    @Test
    @Transactional
    void archivedModelRemainsReadableButCannotBeSelectedForNewInventoryOrStock() {
        var owner = owner();
        var equipment = model(owner, "Historical equipment", false, TrackingMode.SERIALIZED_ASSET);
        var unit = assets.create(owner, equipment.id(), "Historical unit", null, List.of());
        models.archive(owner, equipment.id());
        assertThat(assets.get(owner, unit.id()).assetModelName()).isEqualTo("Historical equipment");
        assertThatThrownBy(() -> assets.create(owner, equipment.id(), "New unit", null, List.of()))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("archived");
        var cases = model(owner, "Active cases", true, TrackingMode.SERIALIZED_ASSET);
        var box = assets.create(owner, cases.id(), "Stock case", null, List.of());
        var consumable = model(owner, "Historical consumable", false, TrackingMode.QUANTITY_STOCK);
        var balance = stocks.receive(owner, consumable.id(), box.id(), BigDecimal.ONE, "Historical receipt");
        models.archive(owner, consumable.id());
        assertThat(stocks.get(owner, balance.id()).assetModelName()).isEqualTo("Historical consumable");
        assertThat(stocks.ledger(owner, balance.id())).hasSize(1);
        assertThatThrownBy(() -> stocks.receive(owner, consumable.id(), box.id(), BigDecimal.ONE, "New receipt"))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("archived");
    }

    private AssetSearchPageView search(
            TarpeistoPrincipal owner,
            String query,
            boolean inactive,
            boolean archived,
            int limit,
            String cursor,
            String auditState) {
        return assets.search(
                owner,
                query,
                null,
                null,
                inactive,
                "name",
                "asc",
                limit,
                cursor,
                new AssetSearchFilter(archived, null, null, null, null, null, null, auditState, null, null));
    }

    private AssetModelView model(TarpeistoPrincipal owner, String name, boolean container, TrackingMode mode) {
        return models.create(
                owner,
                name,
                null,
                null,
                null,
                mode,
                mode == TrackingMode.QUANTITY_STOCK ? "roll" : null,
                mode == TrackingMode.QUANTITY_STOCK ? BigDecimal.ONE : null,
                container);
    }

    private TarpeistoPrincipal owner() {
        UUID org = UUID.randomUUID(), user = UUID.randomUUID();
        jdbc.sql("INSERT INTO organization(id,name,created_at,updated_at,version) VALUES(:id,:name,now(),now(),0)")
                .param("id", org)
                .param("name", "Search " + org)
                .update();
        String name = "owner-" + user;
        jdbc.sql("""
            INSERT INTO app_user(id,username,display_name,password_hash,enabled,created_at,updated_at,version)
            VALUES(:id,:name,'Owner','test-hash',true,now(),now(),0)
            """).param("id", user).param("name", name).update();
        jdbc.sql("""
            INSERT INTO organization_membership(id,organization_id,user_id,role,created_at,updated_at,version)
            VALUES(:id,:org,:user,'OWNER',now(),now(),0)
            """)
                .param("id", UUID.randomUUID())
                .param("org", org)
                .param("user", user)
                .update();
        return new TarpeistoPrincipal(user, name, "Owner", org, OrganizationRole.OWNER);
    }
}

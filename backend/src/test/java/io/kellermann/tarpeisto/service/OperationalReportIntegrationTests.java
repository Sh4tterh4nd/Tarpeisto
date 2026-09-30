package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.ArchiveKind;
import io.kellermann.tarpeisto.model.BookingLineType;
import io.kellermann.tarpeisto.model.DashboardQueue;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.ReportKind;
import io.kellermann.tarpeisto.model.StockMovementReason;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

class OperationalReportIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private OperationalReportService reports;

    @Autowired
    private AssetModelService models;

    @Autowired
    private AssetService assets;

    @Autowired
    private ConsumableStockService stocks;

    @Autowired
    private LocationService locations;

    @Autowired
    private AuditService audits;

    @Autowired
    private ArchiveService archives;

    @Autowired
    private BookingService bookings;

    @Autowired
    private BookingReservationService reservations;

    @Autowired
    private CheckoutService checkout;

    @Autowired
    private DashboardService dashboard;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private java.time.Clock clock;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    @Transactional
    void realDatabaseReportsContainAllAssetsDecimalsAuditActorsAndOnlyCurrentTenant() throws Exception {
        var owner = owner();
        var other = owner();
        var model = models.create(
                owner, "=Inventory \u0394", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, false);
        var units = assets.createBulk(owner, model.id(), 605, null);
        var otherModel = models.create(
                other, "Foreign equipment", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, false);
        var outside = assets.create(other, otherModel.id(), null, null, List.of());
        var stockModel = models.create(
                owner, "Tape", null, null, null, TrackingMode.QUANTITY_STOCK, "roll", BigDecimal.ONE, false);
        var place = locations.create(owner, "Warehouse", null, null);
        var balance = stocks.changeAtPlace(
                owner,
                stockModel.id(),
                null,
                place.id(),
                new BigDecimal("2.250"),
                StockMovementReason.RECEIPT,
                "=receipt \u0394",
                null,
                null);
        stocks.changeAtPlace(
                owner,
                stockModel.id(),
                null,
                place.id(),
                new BigDecimal("-1.125"),
                StockMovementReason.CONSUMPTION,
                "consumed",
                null,
                null);
        var boxModel = models.create(owner, "Case", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, true);
        var box = assets.create(owner, boxModel.id(), "Report case", null, List.of());
        var audit = audits.launchContainerAudit(owner, box.id(), box.publicCode(), UUID.randomUUID());
        audits.complete(owner, audit.id(), UUID.randomUUID(), box.publicCode(), false, false);
        entityManager.flush();
        for (var kind : ReportKind.values()) {
            try (var artifact = reports.create(owner, kind, null)) {
                var csv = Files.readString(artifact.path());
                assertThat(csv).doesNotContain(outside.id().toString(), "Foreign equipment");
                switch (kind) {
                    case INVENTORY -> {
                        for (var unit : units)
                            assertThat(csv).contains(unit.id().toString());
                        assertThat(csv).contains("'=Inventory \u0394");
                        assertThat(csv.lines()
                                        .filter(line -> line.startsWith("\"ASSET\""))
                                        .count())
                                .isEqualTo(606);
                    }
                    case CONSUMABLE_BALANCES ->
                        assertThat(csv).contains(balance.id().toString(), "1.125");
                    case STOCK_MOVEMENTS ->
                        assertThat(csv)
                                .contains(
                                        "\"-1.125\"",
                                        "'=receipt \u0394",
                                        owner.userId().toString(),
                                        "Owner");
                    case AUDITS ->
                        assertThat(csv)
                                .contains(
                                        audit.id().toString(),
                                        "\"AUDIT\"",
                                        owner.userId().toString(),
                                        "Owner",
                                        "COMPLETED");
                }
            }
        }
        try (var artifact = reports.create(other, ReportKind.AUDITS, null)) {
            assertThat(Files.readString(artifact.path()).split("\r\n")).hasSize(1);
        }
        assertThatThrownBy(() -> reports.create(other, ReportKind.AUDITS, audit.id()))
                .hasMessageContaining("Audit not found");
    }

    @Test
    @Transactional
    void consumableOnlyCheckoutAppearsUntilAccountingAndArchivedSourceCannotBeBooked() {
        var owner = owner();
        var place = locations.create(owner, "Warehouse", null, null);
        var model = models.create(owner, "Stock", null, null, null, TrackingMode.QUANTITY_STOCK, "roll", null, false);
        var balance = stocks.changeAtPlace(
                owner,
                model.id(),
                null,
                place.id(),
                new BigDecimal("5"),
                StockMovementReason.RECEIPT,
                "receipt",
                null,
                null);
        var booking = bookings.create(
                owner,
                UUID.randomUUID(),
                "Consumable-only",
                null,
                null,
                null,
                clock.instant().minusSeconds(30),
                clock.instant().plusSeconds(3600));
        bookings.addLine(
                owner,
                booking.id(),
                booking.version(),
                BookingLineType.CONSUMABLE,
                null,
                balance.id(),
                new BigDecimal("5"));
        reservations.reserve(
                owner, booking.id(), bookings.get(owner, booking.id()).version());
        var manifest = checkout.checkout(
                owner, booking.id(), bookings.get(owner, booking.id()).version(), UUID.randomUUID(), null, List.of());
        entityManager.flush();
        assertThat(dashboard
                        .page(owner, DashboardQueue.OUTSTANDING_CUSTODY, 25, null)
                        .items())
                .extracting(DashboardService.Row::id)
                .contains(booking.id());
        checkout.returnConsumable(
                owner,
                booking.id(),
                manifest.consumables().getFirst().id(),
                BigDecimal.ONE,
                UUID.randomUUID(),
                null,
                place.id());
        checkout.completeReturn(owner, booking.id(), UUID.randomUUID());
        entityManager.flush();
        assertThat(dashboard
                        .page(owner, DashboardQueue.OUTSTANDING_CUSTODY, 25, null)
                        .items())
                .isEmpty();
        stocks.changeAtPlace(
                owner,
                model.id(),
                null,
                place.id(),
                BigDecimal.ONE.negate(),
                StockMovementReason.CONSUMPTION,
                "consumed return",
                null,
                null);
        entityManager.flush();
        entityManager.clear();
        var empty = stocks.get(owner, balance.id());
        archives.change(owner, ArchiveKind.STOCK, empty.id(), true, empty.version());
        var draft = bookings.create(
                owner,
                UUID.randomUUID(),
                "New draft",
                null,
                null,
                null,
                clock.instant(),
                clock.instant().plusSeconds(3600));
        assertThatThrownBy(() -> bookings.addLine(
                        owner,
                        draft.id(),
                        draft.version(),
                        BookingLineType.CONSUMABLE,
                        null,
                        balance.id(),
                        BigDecimal.ONE))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Restore archived");
    }

    private TarpeistoPrincipal owner() {
        UUID org = UUID.randomUUID(), id = UUID.randomUUID();
        jdbc.sql("INSERT INTO organization(id,name,created_at,updated_at,version) VALUES(:id,:name,now(),now(),0)")
                .param("id", org)
                .param("name", "Archive " + org)
                .update();
        String name = "owner-" + id;
        jdbc.sql(
                        "INSERT INTO app_user(id,username,display_name,password_hash,enabled,created_at,updated_at,version) VALUES(:id,:name,'Owner','test-hash',true,now(),now(),0)")
                .param("id", id)
                .param("name", name)
                .update();
        jdbc.sql(
                        "INSERT INTO organization_membership(id,organization_id,user_id,role,created_at,updated_at,version) VALUES(:id,:org,:user,'OWNER',now(),now(),0)")
                .param("id", UUID.randomUUID())
                .param("org", org)
                .param("user", id)
                .update();
        return new TarpeistoPrincipal(id, name, "Owner", org, OrganizationRole.OWNER);
    }
}

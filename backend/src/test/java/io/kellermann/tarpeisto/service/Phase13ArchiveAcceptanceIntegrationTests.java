package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.ArchiveConflictException;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.model.ArchiveKind;
import io.kellermann.tarpeisto.model.BookingLineType;
import io.kellermann.tarpeisto.model.BookingStatus;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.StockMovementReason;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.security.TemporaryAccessContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

/** Operational archive acceptance against PostgreSQL, including concurrent Owner administration. */
class Phase13ArchiveAcceptanceIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private ArchiveService archives;

    @Autowired
    private AssetModelService models;

    @Autowired
    private AssetService assets;

    @Autowired
    private AssetPlacementService placements;

    @Autowired
    private ConsumableStockService stocks;

    @Autowired
    private AuditService audits;

    @Autowired
    private AssetSealService seals;

    @Autowired
    private UserService users;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private BookingService bookings;

    @Autowired
    private BookingReservationService reservations;

    @Autowired
    private CheckoutService checkout;

    @Autowired
    private LocationService locations;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    @Transactional
    void archivedZeroBalanceKeepsItsIdentityAndLedgerAndRequiresRestoreBeforeReceipt() {
        var owner = owner();
        var box = box(owner, "Stock box");
        UUID model = quantityModel(owner);
        var received = stocks.receive(owner, model, box.id(), new BigDecimal("1.125"), "Initial receipt");
        assertThatThrownBy(() -> archives.change(owner, ArchiveKind.STOCK, received.id(), true, received.version()))
                .isInstanceOf(ArchiveConflictException.class);
        var empty = stocks.consume(owner, model, box.id(), new BigDecimal("1.125"), "Used in warehouse");
        int movements = stocks.ledger(owner, empty.id()).size();
        var archived = archives.change(owner, ArchiveKind.STOCK, empty.id(), true, empty.version());
        assertThat(stocks.listByModel(owner, model)).isEmpty();
        assertThat(stocks.ledger(owner, empty.id())).hasSize(movements);
        assertThat(stocks.get(owner, empty.id()).quantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> stocks.receive(owner, model, box.id(), BigDecimal.ONE, "Must restore"))
                .satisfies(error -> assertThat(error.getMessage()).containsIgnoringCase("archived"));
        archives.change(owner, ArchiveKind.STOCK, empty.id(), false, archived.version());
        var next = stocks.receive(owner, model, box.id(), new BigDecimal("0.375"), "Restored receipt");
        assertThat(next.id()).isEqualTo(empty.id());
        assertThat(next.quantity()).isEqualByComparingTo("0.375");
        assertThat(stocks.ledger(owner, empty.id())).hasSize(movements + 1);
    }

    @Test
    @Transactional
    void ancestorAuditBlocksArchivingEvenWhenChildTaskHasCompleted() {
        var owner = owner();
        var root = box(owner, "Root");
        var child = box(owner, "Child");
        placements.move(
                owner,
                child.id(),
                null,
                root.id(),
                placements.get(owner, child.id()).version());
        UUID model = quantityModel(owner);
        stocks.receive(owner, model, child.id(), BigDecimal.ONE, "Receipt");
        var empty = stocks.consume(owner, model, child.id(), BigDecimal.ONE, "Consumption");
        var pending = audits.launchContainerAudit(owner, root.id(), root.publicCode(), UUID.randomUUID());
        var childAudit = audits.start(owner, pending.taskId(), child.publicCode());
        audits.complete(owner, childAudit.id(), UUID.randomUUID(), child.publicCode(), false, false);
        UUID rootTask = jdbc.sql("SELECT id FROM audit_task WHERE organization_id=:org AND container_asset_id=:id")
                .param("org", owner.organizationId())
                .param("id", root.id())
                .query(UUID.class)
                .single();
        var rootAudit = audits.start(owner, rootTask, root.publicCode());
        assertThat(rootAudit.state()).isEqualTo("IN_PROGRESS");
        assertThatThrownBy(() -> archives.change(owner, ArchiveKind.STOCK, empty.id(), true, empty.version()))
                .isInstanceOf(ArchiveConflictException.class);
        assertThatThrownBy(() -> archives.change(owner, ArchiveKind.AUDIT, childAudit.id(), true, 0))
                .isInstanceOf(ArchiveConflictException.class);
    }

    @Test
    @Transactional
    void archivedCompletedAuditPreservesFactsAndNewChangesNeedFreshVerification() {
        var owner = owner();
        var box = box(owner, "Historical case");
        var first = audits.launchContainerAudit(owner, box.id(), box.publicCode(), UUID.randomUUID());
        audits.complete(owner, first.id(), UUID.randomUUID(), box.publicCode(), false, false);
        var second = audits.launchContainerAudit(owner, box.id(), box.publicCode(), UUID.randomUUID());
        assertThat(second.taskId()).isNotEqualTo(first.taskId());
        audits.complete(owner, second.id(), UUID.randomUUID(), box.publicCode(), false, false);
        entityManager.flush();
        String snapshot = auditSnapshot(second.id());
        archives.change(owner, ArchiveKind.AUDIT, second.id(), true, 0);
        seals.invalidate(owner, box.id(), "Later packing configuration");
        assertThat(auditSnapshot(second.id())).isEqualTo(snapshot);
        assertThat(jdbc.sql("SELECT state FROM audit_task WHERE id=:id")
                        .param("id", second.taskId())
                        .query(String.class)
                        .single())
                .isEqualTo("COMPLETED");
        assertThat(assets.get(owner, box.id()).lastVerifiedAuditId()).isNull();
        var fresh = audits.launchContainerAudit(owner, box.id(), box.publicCode(), UUID.randomUUID());
        assertThat(fresh.taskId()).isNotEqualTo(second.taskId());
        assertThat(fresh.id()).isNotEqualTo(second.id());
    }

    @Test
    @Transactional
    void serviceArchiveRejectsCrossTenantAndTemporaryActors() {
        var owner = owner();
        var other = owner();
        var target = users.createUser(
                owner,
                "scoped-" + UUID.randomUUID(),
                "long-test-password",
                "Scoped user",
                null,
                OrganizationRole.VIEWER);
        assertThatThrownBy(() -> archives.change(other, ArchiveKind.USER, target.id(), true, target.version()))
                .isInstanceOf(NotFoundException.class);
        var temporary = new TarpeistoPrincipal(
                owner.userId(),
                owner.username(),
                owner.displayName(),
                owner.organizationId(),
                OrganizationRole.OWNER,
                new TemporaryAccessContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        clock.instant().plusSeconds(3600)));
        assertThatThrownBy(() -> archives.change(temporary, ArchiveKind.USER, target.id(), true, target.version()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @Transactional
    void eventArchiveRequiresAccountingAndKeepsConsumedQuantityAndClosedHistory() {
        var owner = owner();
        var box = box(owner, "Event case");
        var location = locations.create(owner, "Warehouse", null, null);
        UUID model = quantityModel(owner);
        var received = stocks.changeAtPlace(
                owner,
                model,
                null,
                location.id(),
                new BigDecimal("5"),
                StockMovementReason.RECEIPT,
                "Receipt",
                null,
                null);
        var booking = bookings.create(
                owner,
                UUID.randomUUID(),
                "Archive acceptance event",
                null,
                null,
                null,
                Instant.parse("2027-05-01T10:00:00Z"),
                Instant.parse("2027-05-02T10:00:00Z"));
        bookings.addLine(
                owner, booking.id(), booking.version(), BookingLineType.CONTAINER, box.id(), null, BigDecimal.ONE);
        bookings.addLine(
                owner,
                booking.id(),
                bookings.get(owner, booking.id()).version(),
                BookingLineType.CONSUMABLE,
                null,
                received.id(),
                new BigDecimal("5"));
        assertThat(reservations
                        .reserve(
                                owner,
                                booking.id(),
                                bookings.get(owner, booking.id()).version())
                        .reservable())
                .isTrue();
        var manifest = checkout.checkout(
                owner, booking.id(), bookings.get(owner, booking.id()).version(), UUID.randomUUID(), null, List.of());
        checkout.checkInAsset(owner, booking.id(), box.id(), UUID.randomUUID());
        UUID task = checkout.get(owner, booking.id()).auditTasks().getFirst().id();
        var audit = audits.start(owner, task, box.publicCode());
        audits.complete(owner, audit.id(), UUID.randomUUID(), box.publicCode(), false, false);
        entityManager.flush();
        entityManager.clear();
        assertThatThrownBy(() -> archives.change(owner, ArchiveKind.AUDIT, audit.id(), true, 0))
                .isInstanceOf(ArchiveConflictException.class);
        assertThatThrownBy(() -> archives.change(
                        owner,
                        ArchiveKind.STOCK,
                        received.id(),
                        true,
                        stocks.get(owner, received.id()).version()))
                .isInstanceOf(ArchiveConflictException.class)
                .hasMessageContaining("operational work");
        checkout.returnConsumable(
                owner,
                booking.id(),
                manifest.consumables().getFirst().id(),
                new BigDecimal("2"),
                UUID.randomUUID(),
                null,
                location.id());
        checkout.completeReturn(owner, booking.id(), UUID.randomUUID());
        entityManager.flush();
        var closed = bookings.get(owner, booking.id());
        assertThat(closed.status()).isEqualTo(BookingStatus.COMPLETED);
        var accounted = checkout.get(owner, booking.id()).consumables().getFirst();
        assertThat(accounted.returnedQuantity()).isEqualByComparingTo("2");
        assertThat(accounted.consumedQuantity()).isEqualByComparingTo("3");
        var empty = stocks.changeAtPlace(
                owner,
                model,
                null,
                location.id(),
                new BigDecimal("-2"),
                StockMovementReason.CONSUMPTION,
                "Warehouse use",
                null,
                null);
        archives.change(owner, ArchiveKind.STOCK, empty.id(), true, empty.version());
        archives.change(owner, ArchiveKind.BOOKING, closed.id(), true, closed.version());
        archives.change(owner, ArchiveKind.AUDIT, audit.id(), true, 0);
        seals.invalidate(owner, box.id(), "New packing after archived event");
        entityManager.flush();
        assertThat(bookings.get(owner, closed.id()).status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(checkout.get(owner, closed.id()).consumables()).containsExactly(accounted);
        assertThat(jdbc.sql("SELECT state FROM audit_task WHERE id=:id")
                        .param("id", task)
                        .query(String.class)
                        .single())
                .isEqualTo("COMPLETED");
    }

    @Test
    void concurrentArchiveAndDisableCannotRemoveBothLocalOwners() throws Exception {
        var first = owner();
        var second = users.createUser(
                first,
                "other-owner-" + UUID.randomUUID(),
                "long-test-password",
                "Other Owner",
                null,
                OrganizationRole.OWNER);
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Boolean> archive =
                    () -> race(ready, start, () -> archives.change(first, ArchiveKind.USER, first.userId(), true, 0));
            Callable<Boolean> disable = () -> race(ready, start, () -> users.setEnabled(first, second.id(), false));
            var a = executor.submit(archive);
            var b = executor.submit(disable);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.sql("""
                SELECT count(*) FROM app_user u JOIN organization_membership m ON m.user_id=u.id
                WHERE m.organization_id=:org AND m.role='OWNER' AND u.enabled
                  AND u.archived_at IS NULL AND u.password_hash IS NOT NULL
                """)
                            .param("org", first.organizationId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private boolean race(CountDownLatch ready, CountDownLatch start, Runnable action) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out starting Owner race");
        try {
            action.run();
            return true;
        } catch (ArchiveConflictException | io.kellermann.tarpeisto.exception.ValidationFailedException expected) {
            return false;
        }
    }

    private String auditSnapshot(UUID id) {
        return jdbc.sql("SELECT row_to_json(a)::text FROM container_audit a WHERE id=:id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private UUID quantityModel(TarpeistoPrincipal owner) {
        return models.create(
                        owner,
                        "Consumable " + UUID.randomUUID(),
                        null,
                        null,
                        null,
                        TrackingMode.QUANTITY_STOCK,
                        "roll",
                        new BigDecimal("2.5"),
                        false)
                .id();
    }

    private AssetView box(TarpeistoPrincipal owner, String name) {
        var model = models.create(
                owner, "Cases " + UUID.randomUUID(), null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, true);
        return assets.create(owner, model.id(), name, null, List.of());
    }

    private TarpeistoPrincipal owner() {
        UUID org = UUID.randomUUID(), id = UUID.randomUUID();
        jdbc.sql("INSERT INTO organization(id,name,created_at,updated_at,version) VALUES(:id,:name,now(),now(),0)")
                .param("id", org)
                .param("name", "Acceptance " + org)
                .update();
        String name = "owner-" + id;
        jdbc.sql("""
            INSERT INTO app_user(id,username,display_name,password_hash,enabled,created_at,updated_at,version)
            VALUES(:id,:name,'Owner','test-hash',true,now(),now(),0)
            """).param("id", id).param("name", name).update();
        jdbc.sql("""
            INSERT INTO organization_membership(id,organization_id,user_id,role,created_at,updated_at,version)
            VALUES(:id,:org,:user,'OWNER',now(),now(),0)
            """)
                .param("id", UUID.randomUUID())
                .param("org", org)
                .param("user", id)
                .update();
        return new TarpeistoPrincipal(id, name, "Owner", org, OrganizationRole.OWNER);
    }
}

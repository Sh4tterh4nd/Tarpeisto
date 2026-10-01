package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.AuditFindingType;
import io.kellermann.tarpeisto.model.LifecycleState;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real committed transactions exercise the final-state callback as well as bounded backfill. */
class PackingFindingReconciliationIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private PackingFindingReconciliationService reconciliation;

    @Autowired
    private PackingRequirementService packing;

    @Autowired
    private AssetPlacementService placements;

    @Autowired
    private AssetModelService models;

    @Autowired
    private AssetService assets;

    @Autowired
    private AuditService audits;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ConsumableStockService stocks;

    @Autowired
    private BookingService bookings;

    @Autowired
    private BookingReservationService reservations;

    @Autowired
    private CheckoutService checkout;

    @Autowired
    private java.time.Clock clock;

    @Autowired
    private org.springframework.boot.resttestclient.TestRestTemplate http;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwords;

    @Autowired
    private org.springframework.session.jdbc.JdbcIndexedSessionRepository sessions;

    @Autowired
    private TemporaryAccessService temporaryAccess;

    @Test
    void twentyScansAgainstTenRequiredBecomeTenAttributedDismissalsOnlyAfterFinalUpdate() {
        var f = fixture(10, 20);
        var audit = complete(f);
        String observations = observations(audit.id());
        assertThat(resolutionCount(f.owner)).isZero();
        packing.update(
                f.owner,
                f.requirement.id(),
                f.requirement.version(),
                PackingRequirementType.MODEL_QUANTITY,
                f.model,
                null,
                BigDecimal.valueOf(20));
        assertThat(resolutionCount(f.owner)).isEqualTo(10);
        assertThat(observations(audit.id())).isEqualTo(observations);
        assertThat(jdbc.sql("SELECT DISTINCT actor_user_id FROM audit_finding_resolution WHERE organization_id=:org")
                        .param("org", f.owner.organizationId())
                        .query(UUID.class)
                        .list())
                .containsExactly(f.owner.userId());
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM activity_log WHERE organization_id=:org AND action='FINDING_RESOLVED'")
                        .param("org", f.owner.organizationId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(10);
        assertThat(jdbc.sql("SELECT state FROM container_audit WHERE organization_id=:org AND current_attempt")
                        .param("org", f.owner.organizationId())
                        .query(String.class)
                        .list())
                .doesNotContain("COMPLETED");
        assertThat(reconciliation.sweep(f.owner, null).dismissedCount()).isZero();
        assertThat(resolutionCount(f.owner)).isEqualTo(10);
    }

    @Test
    void tenPresentAgainstTwentyRequiredKeepsShortageAndAnyOtherDeficiencyPreventsDismissal() {
        var f = fixture(20, 10);
        complete(f);
        assertThat(reconciliation.sweep(f.owner, null).dismissedCount()).isZero();
        packing.update(
                f.owner,
                f.requirement.id(),
                f.requirement.version(),
                PackingRequirementType.MODEL_QUANTITY,
                f.model,
                null,
                BigDecimal.valueOf(20));
        assertThat(resolutionCount(f.owner)).isZero();
        packing.update(
                f.owner,
                f.requirement.id(),
                packing.list(f.owner, f.box.id()).getFirst().version(),
                PackingRequirementType.MODEL_QUANTITY,
                f.model,
                null,
                BigDecimal.TEN);
        assertThat(resolutionCount(f.owner)).isEqualTo(1);
    }

    @Test
    void movingExtraAwayDismissesButInactiveStillParentedNeverDisappearsAsProof() {
        var f = fixture(1, 2);
        complete(f);
        var extra = f.units.getLast();
        assets.changeLifecycleState(f.owner, extra.id(), LifecycleState.RETIRED, "Retired but still in case");
        assertThat(packing.preview(f.owner, f.box.id(), null).complete()).isTrue();
        assertThat(reconciliation.sweep(f.owner, null).dismissedCount()).isZero();
        placements.move(
                f.owner,
                extra.id(),
                null,
                null,
                placements.get(f.owner, extra.id()).version());
        assertThat(resolutionCount(f.owner)).isEqualTo(1);
    }

    @Test
    void outerTransactionFinalDeficiencyPreventsPrematureNestedPlacementDismissalAndRollbackLeavesNothing() {
        var f = fixture(1, 2);
        complete(f);
        var extra = f.units.getLast();
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            placements.move(
                    f.owner,
                    extra.id(),
                    null,
                    null,
                    placements.get(f.owner, extra.id()).version());
            packing.update(
                    f.owner,
                    f.requirement.id(),
                    packing.list(f.owner, f.box.id()).getFirst().version(),
                    PackingRequirementType.MODEL_QUANTITY,
                    f.model,
                    null,
                    BigDecimal.TEN);
        });
        assertThat(resolutionCount(f.owner)).isZero();
        tx.executeWithoutResult(status -> {
            packing.update(
                    f.owner,
                    f.requirement.id(),
                    packing.list(f.owner, f.box.id()).getFirst().version(),
                    PackingRequirementType.MODEL_QUANTITY,
                    f.model,
                    null,
                    BigDecimal.ONE);
            status.setRollbackOnly();
        });
        assertThat(resolutionCount(f.owner)).isZero();
        assertThat(packing.list(f.owner, f.box.id()).getFirst().requiredQuantity())
                .isEqualByComparingTo(BigDecimal.TEN);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    packing.update(
                            f.owner,
                            f.requirement.id(),
                            packing.list(f.owner, f.box.id()).getFirst().version(),
                            PackingRequirementType.MODEL_QUANTITY,
                            f.model,
                            null,
                            BigDecimal.ONE);
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void beforeCommit(boolean readOnly) {
                                    assertThat(resolutionCount(f.owner)).isEqualTo(1);
                                    throw new IllegalStateException("Force rollback after reconciliation appended");
                                }
                            });
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Force rollback");
        assertThat(resolutionCount(f.owner)).isZero();
        assertThat(packing.list(f.owner, f.box.id()).getFirst().requiredQuantity())
                .isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void manualManifestMalformedAndForeignExpectedLinksRemainUnresolved() {
        var f = fixture(1, 1);
        var audit = audits.launchContainerAudit(f.owner, f.box.id(), f.box.publicCode(), UUID.randomUUID());
        for (var unit : f.units) audits.scan(f.owner, audit.id(), UUID.randomUUID(), unit.publicCode());
        UUID expected = jdbc.sql("SELECT id FROM audit_expected_requirement WHERE container_audit_id=:audit")
                .param("audit", audit.id())
                .query(UUID.class)
                .single();
        insertFinding(f, audit.id(), "{\"expectedId\":\"" + expected + "\",\"eventManifest\":false}", null);
        insertFinding(f, audit.id(), "{\"expectedId\":\"" + expected + "\"}", UUID.randomUUID());
        insertFinding(f, audit.id(), "{\"expectedId\":\"invalid\"}", null);
        insertFinding(f, audit.id(), "{\"expectedId\":\"" + UUID.randomUUID() + "\"}", null);
        audits.complete(f.owner, audit.id(), UUID.randomUUID(), f.box.publicCode(), true, false);
        assertThat(reconciliation.sweep(f.owner, null).dismissedCount()).isZero();
        assertThat(resolutionCount(f.owner)).isZero();
    }

    @Test
    void cursorIsBoundedAdvancesAcrossRejectedRowsAndRejectsAnotherTenant() {
        var f = fixture(1, 1);
        var audit = audits.launchContainerAudit(f.owner, f.box.id(), f.box.publicCode(), UUID.randomUUID());
        audits.scan(f.owner, audit.id(), UUID.randomUUID(), f.units.getFirst().publicCode());
        for (int i = 0; i < 205; i++) insertFinding(f, audit.id(), "{}", null);
        audits.complete(f.owner, audit.id(), UUID.randomUUID(), f.box.publicCode(), false, false);
        var first = reconciliation.sweep(f.owner, null);
        assertThat(first.inspectedCount()).isEqualTo(100);
        assertThat(first.dismissedCount()).isZero();
        assertThat(first.nextCursor()).isNotNull();
        assertThatThrownBy(() -> reconciliation.sweep(owner(), first.nextCursor()))
                .isInstanceOf(ValidationFailedException.class);
        var second = reconciliation.sweep(f.owner, first.nextCursor());
        var third = reconciliation.sweep(f.owner, second.nextCursor());
        assertThat(second.inspectedCount()).isEqualTo(100);
        assertThat(third.inspectedCount()).isEqualTo(5);
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void activeAuditAndInactiveSourceAreSkippedWithoutBreakingBackfill() {
        var f = fixture(2, 1);
        complete(f);
        packing.update(
                f.owner,
                f.requirement.id(),
                f.requirement.version(),
                PackingRequirementType.MODEL_QUANTITY,
                f.model,
                null,
                BigDecimal.TEN);
        // Start the new current attempt before making current contents match through legacy persisted state.
        UUID current = jdbc.sql("SELECT id FROM audit_task WHERE organization_id=:org AND state='READY'")
                .param("org", f.owner.organizationId())
                .query(UUID.class)
                .single();
        audits.start(f.owner, current, f.box.publicCode());
        jdbc.sql("UPDATE packing_requirement SET required_quantity=1 WHERE id=:id")
                .param("id", f.requirement.id())
                .update();
        assertThat(reconciliation.sweep(f.owner, null).dismissedCount()).isZero();
        assets.changeLifecycleState(f.owner, f.box.id(), LifecycleState.RETIRED, "Retired source");
        assertThat(reconciliation.sweep(f.owner, null).dismissedCount()).isZero();
    }

    @Test
    void archivedSourceIsSkippedIndependentlyOfInProgressGuard() {
        var f = fixture(2, 1);
        complete(f);
        jdbc.sql("UPDATE packing_requirement SET required_quantity=1 WHERE id=:id")
                .param("id", f.requirement.id())
                .update();
        // Legacy archived source with stale findings must not fail or auto-resolve the sweep.
        jdbc.sql("UPDATE physical_asset SET archived_at=now() WHERE id=:id")
                .param("id", f.box.id())
                .update();
        assertThat(jdbc.sql("SELECT count(*) FROM container_audit WHERE organization_id=:org AND state='IN_PROGRESS'")
                        .param("org", f.owner.organizationId())
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(reconciliation.sweep(f.owner, null).dismissedCount()).isZero();
        assertThat(resolutionCount(f.owner)).isZero();
    }

    @Test
    void concurrentBackfillAppendsExactlyOneResolutionAndActivity() throws Exception {
        var f = fixture(1, 2);
        var audit = complete(f);
        jdbc.sql("UPDATE packing_requirement SET required_quantity=2 WHERE id=:id")
                .param("id", f.requirement.id())
                .update();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var work = (java.util.concurrent.Callable<Integer>) () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                return reconciliation.sweep(f.owner, null).dismissedCount();
            };
            var one = executor.submit(work);
            var two = executor.submit(work);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(one.get(30, TimeUnit.SECONDS) + two.get(30, TimeUnit.SECONDS))
                    .isEqualTo(1);
        }
        assertThat(resolutionCount(f.owner)).isEqualTo(1);
        assertThat(observations(audit.id())).contains("UNEXPECTED");
    }

    @Test
    void serviceRejectsNonManagerAndTemporaryBeforePersistence() {
        var owner = owner();
        for (var role : List.of(OrganizationRole.VIEWER, OrganizationRole.OPERATOR_AUDITOR))
            assertThatThrownBy(() -> reconciliation.sweep(
                            new TarpeistoPrincipal(
                                    owner.userId(),
                                    owner.username(),
                                    owner.displayName(),
                                    owner.organizationId(),
                                    role),
                            null))
                    .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reconciliation.sweep(null, null)).isInstanceOf(AccessDeniedException.class);
        var temp = new TarpeistoPrincipal(
                owner.userId(),
                owner.username(),
                owner.displayName(),
                owner.organizationId(),
                OrganizationRole.OWNER,
                new io.kellermann.tarpeisto.security.TemporaryAccessContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        java.time.Instant.parse("2030-01-01T00:00:00Z")));
        assertThatThrownBy(() -> reconciliation.sweep(temp, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void removingExactPinRechecksItsCurrentParentAndDismissesOriginalMisplacedOnlyWhenComplete() {
        var f = fixture(1, 2);
        var otherModel =
                models.create(f.owner, "Other case", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, true);
        var other = assets.create(f.owner, otherModel.id(), "Other named case", null, List.of());
        var pin = packing.add(
                f.owner,
                other.id(),
                PackingRequirementType.SPECIFIC_ASSET,
                null,
                f.units.getLast().id().toString(),
                BigDecimal.ONE);
        var completed = complete(f);
        assertThat(completed.findings()).anyMatch(finding -> finding.type().equals("MISPLACED"));
        packing.update(
                f.owner,
                f.requirement.id(),
                f.requirement.version(),
                PackingRequirementType.MODEL_QUANTITY,
                f.model,
                null,
                BigDecimal.valueOf(2));
        assertThat(resolutionCount(f.owner)).isZero();
        packing.archive(f.owner, pin.id(), pin.version());
        assertThat(resolutionCount(f.owner)).isEqualTo(1);
    }

    @Test
    void templateAndExactAssignmentNeverResolveAnIntermediateCompleteState() {
        var f = fixture(1, 2);
        complete(f);
        packing.archive(f.owner, f.requirement.id(), f.requirement.version());
        var template = packing.createTemplate(f.owner, "Final-state template", null);
        packing.addTemplateRequirement(
                f.owner, template.id(), PackingRequirementType.MODEL_QUANTITY, f.model, null, BigDecimal.valueOf(2));
        UUID unavailable = models.create(
                        f.owner,
                        "Other required model",
                        null,
                        null,
                        null,
                        TrackingMode.SERIALIZED_ASSET,
                        null,
                        null,
                        false)
                .id();
        packing.addTemplateRequirement(
                f.owner, template.id(), PackingRequirementType.MODEL_QUANTITY, unavailable, null, BigDecimal.ONE);
        packing.applyTemplate(f.owner, f.box.id(), template.id());
        assertThat(resolutionCount(f.owner)).isZero();
        assertThat(packing.preview(f.owner, f.box.id(), null).complete()).isFalse();
        var missing = packing.list(f.owner, f.box.id()).stream()
                .filter(row -> unavailable.equals(row.assetModelId()))
                .findFirst()
                .orElseThrow();
        packing.archive(f.owner, missing.id(), missing.version());
        assertThat(resolutionCount(f.owner)).isEqualTo(1);

        var g = fixture(2, 1);
        complete(g);
        var exact = g.units.getFirst();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            packing.archive(g.owner, g.requirement.id(), g.requirement.version());
            packing.add(
                    g.owner,
                    g.box.id(),
                    PackingRequirementType.SPECIFIC_ASSET,
                    null,
                    exact.id(),
                    null,
                    BigDecimal.ONE,
                    true,
                    placements.get(g.owner, exact.id()).version());
            packing.add(
                    g.owner,
                    g.box.id(),
                    PackingRequirementType.MODEL_QUANTITY,
                    unavailableFor(g.owner),
                    null,
                    BigDecimal.ONE);
        });
        assertThat(resolutionCount(g.owner)).isZero();
    }

    @Test
    void validConsumableLowDamageAndUnreadableObservationsStayImmutableDespiteSufficientStoredStock() {
        var f = fixture(1, 1);
        packing.update(
                f.owner,
                f.requirement.id(),
                f.requirement.version(),
                PackingRequirementType.SPECIFIC_ASSET,
                null,
                f.units.getFirst().id().toString(),
                BigDecimal.ONE);
        UUID tape = models.create(f.owner, "Tape", null, null, null, TrackingMode.QUANTITY_STOCK, "roll", null, false)
                .id();
        stocks.receive(f.owner, tape, f.box.id(), BigDecimal.TEN, "Initial stock");
        packing.add(f.owner, f.box.id(), PackingRequirementType.CONSUMABLE_QUANTITY, tape, null, BigDecimal.ONE);
        var audit = audits.launchContainerAudit(f.owner, f.box.id(), f.box.publicCode(), UUID.randomUUID());
        audits.scan(f.owner, audit.id(), UUID.randomUUID(), f.units.getFirst().publicCode());
        UUID expected = jdbc.sql(
                        "SELECT id FROM audit_expected_requirement WHERE container_audit_id=:audit AND requirement_type='CONSUMABLE_QUANTITY'")
                .param("audit", audit.id())
                .query(UUID.class)
                .single();
        audits.observeConsumable(
                f.owner,
                audit.id(),
                expected,
                UUID.randomUUID(),
                io.kellermann.tarpeisto.model.AuditConsumableStatus.MISSING_LOW,
                BigDecimal.ZERO,
                "Low observed quantity");
        audits.recordFinding(
                f.owner,
                audit.id(),
                UUID.randomUUID(),
                AuditFindingType.DAMAGED,
                f.units.getFirst().id(),
                "Damage");
        audits.recordFinding(
                f.owner,
                audit.id(),
                UUID.randomUUID(),
                AuditFindingType.UNREADABLE_LABEL,
                f.units.getFirst().id(),
                "Unreadable");
        audits.complete(f.owner, audit.id(), UUID.randomUUID(), f.box.publicCode(), true, false);
        String original = observations(audit.id());
        assertThat(packing.preview(f.owner, f.box.id(), null).complete()).isTrue();
        assertThat(reconciliation.sweep(f.owner, null).dismissedCount()).isZero();
        assertThat(observations(audit.id())).isEqualTo(original);
        assertThat(resolutionCount(f.owner)).isZero();
    }

    @Test
    void eventSweepClearsPackingReviewWithoutReleasingReturnedManifestCustodyOrVerification() {
        var f = fixture(2, 2);
        for (var unit : f.units)
            placements.move(
                    f.owner,
                    unit.id(),
                    null,
                    f.box.id(),
                    placements.get(f.owner, unit.id()).version());
        var booking = bookings.create(
                f.owner,
                UUID.randomUUID(),
                "Real event return",
                null,
                null,
                null,
                clock.instant(),
                clock.instant().plusSeconds(3600));
        bookings.addLine(
                f.owner,
                booking.id(),
                booking.version(),
                io.kellermann.tarpeisto.model.BookingLineType.CONTAINER,
                f.box.id(),
                null,
                BigDecimal.ONE);
        reservations.reserve(
                f.owner, booking.id(), bookings.get(f.owner, booking.id()).version());
        checkout.checkout(
                f.owner,
                booking.id(),
                bookings.get(f.owner, booking.id()).version(),
                UUID.randomUUID(),
                "Authorized initial verification exception",
                List.of());
        checkout.checkInAsset(f.owner, booking.id(), f.box.id(), UUID.randomUUID());
        // Historical mismatch: manifest contains both units, current audit packing expects only one.
        jdbc.sql("UPDATE packing_requirement SET required_quantity=1 WHERE id=:id")
                .param("id", f.requirement.id())
                .update();
        UUID task = checkout.get(f.owner, booking.id()).auditTasks().getFirst().id();
        var audit = audits.start(f.owner, task, f.box.publicCode());
        for (var unit : f.units) audits.scan(f.owner, audit.id(), UUID.randomUUID(), unit.publicCode());
        audits.complete(f.owner, audit.id(), UUID.randomUUID(), f.box.publicCode(), true, false);
        assertThat(resolutionCount(f.owner)).isZero();
        String original = observations(audit.id());
        jdbc.sql("UPDATE packing_requirement SET required_quantity=2 WHERE id=:id")
                .param("id", f.requirement.id())
                .update();
        assertThat(reconciliation.sweep(f.owner, null).dismissedCount()).isEqualTo(1);
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM checkout_manifest_asset WHERE organization_id=:org AND returned_at IS NOT NULL AND audit_released_at IS NULL")
                        .param("org", f.owner.organizationId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM asset_verification_history WHERE organization_id=:org AND verification_state='VERIFIED'")
                        .param("org", f.owner.organizationId())
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(bookings.get(f.owner, booking.id()).status())
                .isEqualTo(io.kellermann.tarpeisto.model.BookingStatus.RETURNED_AUDITS_PENDING);
        assertThat(observations(audit.id())).isEqualTo(original);
    }

    @Test
    void httpRequiresPermanentManagerAndCsrfAndKeepsAnonymousUnauthorized() {
        var owner = owner();
        jdbc.sql("UPDATE app_user SET password_hash=:hash WHERE id=:id")
                .param("hash", passwords.encode("correct"))
                .param("id", owner.userId())
                .update();
        var authenticated =
                io.kellermann.tarpeisto.security.PermissionTestSupport.login(http, owner.username(), "correct");
        String path = "/api/v1/findings/reconcile-packing";
        var headers = authenticated.headers();
        assertThat(http.postForEntity(path, new org.springframework.http.HttpEntity<>(headers), String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
        var noCsrf = authenticated.headers();
        noCsrf.remove("X-XSRF-TOKEN");
        assertThat(http.postForEntity(path, new org.springframework.http.HttpEntity<>(noCsrf), String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        var anonymous = authenticated.headers();
        anonymous.set("Cookie", anonymous.getFirst("Cookie").replaceAll("SESSION=[^;]+;?", ""));
        assertThat(http.postForEntity(path, new org.springframework.http.HttpEntity<>(anonymous), String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
        for (var role : List.of(OrganizationRole.DEPUTY, OrganizationRole.OPERATOR_AUDITOR, OrganizationRole.VIEWER)) {
            jdbc.sql("UPDATE organization_membership SET role=:role WHERE organization_id=:org AND user_id=:user")
                    .param("role", role.name())
                    .param("org", owner.organizationId())
                    .param("user", owner.userId())
                    .update();
            assertThat(http.postForEntity(path, new org.springframework.http.HttpEntity<>(headers), String.class)
                            .getStatusCode())
                    .isEqualTo(
                            role == OrganizationRole.DEPUTY
                                    ? org.springframework.http.HttpStatus.OK
                                    : org.springframework.http.HttpStatus.FORBIDDEN);
        }
        jdbc.sql("UPDATE organization_membership SET role='OWNER' WHERE organization_id=:org AND user_id=:user")
                .param("org", owner.organizationId())
                .param("user", owner.userId())
                .update();
        var event = bookings.create(
                owner,
                UUID.randomUUID(),
                "Volunteer scope",
                null,
                null,
                null,
                clock.instant(),
                clock.instant().plusSeconds(3600));
        var invitation = temporaryAccess.create(owner, event.id(), null);
        var volunteer = temporaryAccess.redeem(invitation.token(), "Helper", UUID.randomUUID());
        @SuppressWarnings("unchecked")
        org.springframework.session.SessionRepository<org.springframework.session.Session> repository =
                (org.springframework.session.SessionRepository<org.springframework.session.Session>)
                        (org.springframework.session.SessionRepository<?>) sessions;
        org.springframework.session.Session session = repository.createSession();
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                volunteer,
                null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("TEMPORARY_AUDITOR"))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        repository.save(session);
        var temporaryHeaders = authenticated.headers();
        String value = java.util.Base64.getEncoder()
                .encodeToString(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        temporaryHeaders.set(
                "Cookie", temporaryHeaders.getFirst("Cookie").replaceAll("SESSION=[^;]+", "SESSION=" + value));
        assertThat(http.postForEntity(path, new org.springframework.http.HttpEntity<>(temporaryHeaders), String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
    }

    private UUID unavailableFor(TarpeistoPrincipal owner) {
        return models.create(owner, "Missing model", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, false)
                .id();
    }

    private Fixture fixture(int required, int scanned) {
        var owner = owner();
        var boxModel = models.create(owner, "Case", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, true);
        var box = assets.create(owner, boxModel.id(), "Named case", null, List.of());
        UUID model = models.create(owner, "Unit", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, false)
                .id();
        var units = assets.createBulk(owner, model, scanned, null);
        var requirement = packing.add(
                owner, box.id(), PackingRequirementType.MODEL_QUANTITY, model, null, BigDecimal.valueOf(required));
        return new Fixture(owner, box, model, units, requirement);
    }

    private ContainerAuditView complete(Fixture f) {
        var audit = audits.launchContainerAudit(f.owner, f.box.id(), f.box.publicCode(), UUID.randomUUID());
        for (var unit : f.units) audits.scan(f.owner, audit.id(), UUID.randomUUID(), unit.publicCode());
        return audits.complete(f.owner, audit.id(), UUID.randomUUID(), f.box.publicCode(), true, false);
    }

    private void insertFinding(Fixture f, UUID audit, String detail, UUID operation) {
        jdbc.sql("""
                INSERT INTO audit_finding(id,organization_id,container_audit_id,finding_type,detail,recorded_by_user_id,recorded_at,source_operation_id)
                VALUES(:id,:org,:audit,'MISSING',CAST(:detail AS jsonb),:actor,now(),:operation)
                """)
                .param("id", UUID.randomUUID())
                .param("org", f.owner.organizationId())
                .param("audit", audit)
                .param("detail", detail)
                .param("actor", f.owner.userId())
                .param("operation", operation, java.sql.Types.OTHER)
                .update();
    }

    private int resolutionCount(TarpeistoPrincipal owner) {
        return jdbc.sql("SELECT count(*) FROM audit_finding_resolution WHERE organization_id=:org")
                .param("org", owner.organizationId())
                .query(Integer.class)
                .single();
    }

    private String observations(UUID audit) {
        return jdbc.sql(
                        "SELECT coalesce(jsonb_agg(to_jsonb(f) ORDER BY id)::text,'[]') FROM audit_finding f WHERE container_audit_id=:audit")
                .param("audit", audit)
                .query(String.class)
                .single();
    }

    private TarpeistoPrincipal owner() {
        UUID org = UUID.randomUUID(), id = UUID.randomUUID();
        jdbc.sql("INSERT INTO organization(id,name,created_at,updated_at,version) VALUES(:id,:name,now(),now(),0)")
                .param("id", org)
                .param("name", "Reconciliation " + org)
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

    private record Fixture(
            TarpeistoPrincipal owner,
            AssetView box,
            UUID model,
            List<AssetView> units,
            PackingRequirementView requirement) {}
}

package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.AuditFindingType;
import io.kellermann.tarpeisto.model.LifecycleState;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AuditScannerRedesignIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private OrganizationService organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private OrganizationMembershipRepository memberships;

    @Autowired
    private AssetModelService models;

    @Autowired
    private AssetService assets;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private PackingRequirementService packing;

    @Autowired
    private AuditService audits;

    @Autowired
    private io.kellermann.tarpeisto.repository.AuditTaskRepository tasks;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;

    @Autowired
    private org.springframework.boot.resttestclient.TestRestTemplate http;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwords;

    @Autowired
    private TemporaryAccessService access;

    @Autowired
    private BookingService bookings;

    @Autowired
    private BookingReservationService reservations;

    @Autowired
    private CheckoutService checkout;

    @Test
    void manualUnreadableModelUnitCountsOnceAndKeepsAnAttributedLabelFinding() {
        var f = fixture(2, 2);
        var op = UUID.randomUUID();
        var accepted = audits.recordFinding(
                f.owner(),
                f.audit().id(),
                op,
                AuditFindingType.UNREADABLE_LABEL,
                f.units().getFirst().id(),
                "Label torn");
        assertThat(accepted.scans()).hasSize(1);
        assertThat(accepted.scans().getFirst().assetModelId())
                .isEqualTo(f.units().getFirst().assetModelId());
        assertThat(accepted.expectedRequirements().getFirst().matchedQuantity()).isEqualByComparingTo("1");
        assertThat(accepted.expectedRequirements().getFirst().satisfied()).isFalse();
        assertThat(accepted.findings()).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("UNREADABLE_LABEL");
            assertThat(r.sourceOperationId()).isEqualTo(op);
            assertThat(r.recordedByDisplayName()).isEqualTo("Scanner owner");
        });
        var replay = audits.recordFinding(
                f.owner(),
                f.audit().id(),
                op,
                AuditFindingType.UNREADABLE_LABEL,
                f.units().getFirst().id(),
                "Label torn");
        assertThat(replay.scans()).hasSize(1);
        assertThat(replay.findings()).hasSize(1);
        var complete = audits.scan(
                f.owner(),
                f.audit().id(),
                UUID.randomUUID(),
                f.units().getLast().publicCode());
        assertThat(complete.expectedRequirements().getFirst().matchedQuantity()).isEqualByComparingTo("2");
        assertThat(complete.expectedRequirements().getFirst().satisfied()).isTrue();
        var undone = audits.undo(
                f.owner(), f.audit().id(), accepted.scans().getFirst().id(), UUID.randomUUID());
        assertThat(undone.expectedRequirements().getFirst().matchedQuantity()).isEqualByComparingTo("1");
    }

    @Test
    void candidatesAreBoundedSearchableAndCursorBoundToActorAuditAndQuery() {
        var f = fixture(105, 105);
        var outside =
                assets.create(f.owner(), f.units().getFirst().assetModelId(), "Outside inventory", null, List.of());
        entityManager.flush();
        var first = audits.manualCandidates(f.owner(), f.audit().id(), null, 100, null);
        assertThat(first.items()).hasSize(100);
        assertThat(first.nextCursor()).isNotNull();
        var second = audits.manualCandidates(f.owner(), f.audit().id(), null, 100, first.nextCursor());
        assertThat(second.items()).hasSize(5);
        assertThat(second.nextCursor()).isNull();
        var ids = new HashSet<UUID>();
        first.items().forEach(r -> ids.add(r.id()));
        second.items().forEach(r -> ids.add(r.id()));
        assertThat(ids).hasSize(105).doesNotContain(outside.id(), f.box().id());
        assertThat(audits.manualCandidates(
                                f.owner(), f.audit().id(), f.units().getFirst().publicCode(), 100, null)
                        .items())
                .singleElement()
                .extracting(AuditManualCandidateView::id)
                .isEqualTo(f.units().getFirst().id());
        assertThat(audits.manualCandidates(f.owner(), f.audit().id(), "%", 100, null)
                        .items())
                .isEmpty();
        assertThatThrownBy(() -> audits.manualCandidates(f.owner(), f.audit().id(), "Cable", 100, first.nextCursor()))
                .isInstanceOf(ValidationFailedException.class);
        var viewer = member(f.owner().organizationId(), OrganizationRole.VIEWER);
        assertThat(audits.manualCandidates(viewer, f.audit().id(), null, 100, null)
                        .items())
                .hasSize(100);
        assertThatThrownBy(() -> audits.manualCandidates(viewer, f.audit().id(), null, 100, first.nextCursor()))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> audits.manualCandidates(owner(), f.audit().id(), null, 100, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> audits.manualCandidates(f.owner(), f.audit().id(), null, 101, null))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void manualLabelRejectsOutsideAndInactiveUnitsWithoutPartialScanOrFinding() {
        var f = fixture(1, 1);
        var outside = assets.create(f.owner(), f.units().getFirst().assetModelId(), "Outside", null, List.of());
        assertThatThrownBy(() -> audits.recordFinding(
                        f.owner(),
                        f.audit().id(),
                        UUID.randomUUID(),
                        AuditFindingType.UNREADABLE_LABEL,
                        outside.id(),
                        null))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("belonging");
        var unit = assetRepository.findById(f.units().getFirst().id()).orElseThrow();
        unit.changeLifecycleState(LifecycleState.RETIRED, clock.instant());
        entityManager.flush();
        assertThat(audits.manualCandidates(f.owner(), f.audit().id(), null, 100, null)
                        .items())
                .singleElement()
                .satisfies(r -> assertThat(r.active()).isFalse());
        assertThatThrownBy(() -> audits.recordFinding(
                        f.owner(),
                        f.audit().id(),
                        UUID.randomUUID(),
                        AuditFindingType.UNREADABLE_LABEL,
                        unit.getId(),
                        null))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("inactive");
        var current = audits.get(f.owner(), f.audit().taskId());
        assertThat(current.scans()).isEmpty();
        assertThat(current.findings()).isEmpty();
    }

    @Test
    void frozenExactCandidatesRemainIdentifiableAfterMoveAndOrdinaryScanSharesMatching() {
        var owner = owner();
        var box = box(owner);
        var model = models.create(
                owner, "Pinned cable", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, false);
        var exact = assets.create(owner, model.id(), null, null, List.of());
        packing.add(owner, box.id(), PackingRequirementType.SPECIFIC_ASSET, null, exact.publicCode(), BigDecimal.ONE);
        var audit = audits.launchContainerAudit(owner, box.id(), box.publicCode(), UUID.randomUUID());
        assertThat(audits.manualCandidates(owner, audit.id(), null, 100, null).items())
                .singleElement()
                .extracting(AuditManualCandidateView::id)
                .isEqualTo(exact.id());
        var found = audits.recordFinding(
                owner, audit.id(), UUID.randomUUID(), AuditFindingType.UNREADABLE_LABEL, exact.id(), "No QR");
        assertThat(found.scans())
                .singleElement()
                .extracting(AuditScanView::outcome)
                .isEqualTo("EXPECTED_EXACT");
        assertThat(found.expectedRequirements().getFirst().matchedQuantity()).isEqualByComparingTo("1");
        assertThat(audits.container(owner, audit.taskId()).sealable()).isFalse();
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void custodyAndUnsafeSelfLabelFailuresRollbackOperationAndAllObservations() {
        var owner = owner();
        var box = box(owner);
        var model = models.create(
                owner, "Custody unit", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, false);
        var unit = assets.create(owner, model.id(), null, null, List.of());
        var event = bookings.create(
                owner,
                UUID.randomUUID(),
                "Other event",
                null,
                null,
                null,
                clock.instant().minusSeconds(30),
                clock.instant().plusSeconds(3600));
        bookings.addLine(
                owner,
                event.id(),
                event.version(),
                io.kellermann.tarpeisto.model.BookingLineType.ASSET,
                unit.id(),
                null,
                BigDecimal.ONE);
        var current = bookings.get(owner, event.id());
        assertThat(reservations.reserve(owner, event.id(), current.version()).reservable())
                .isTrue();
        checkout.checkout(
                owner, event.id(), bookings.get(owner, event.id()).version(), UUID.randomUUID(), null, List.of());
        packing.add(owner, box.id(), PackingRequirementType.SPECIFIC_ASSET, null, unit.publicCode(), BigDecimal.ONE);
        var audit = audits.launchContainerAudit(owner, box.id(), box.publicCode(), UUID.randomUUID());
        var custodyOp = UUID.randomUUID();
        assertThatThrownBy(() -> audits.recordFinding(
                        owner, audit.id(), custodyOp, AuditFindingType.UNREADABLE_LABEL, unit.id(), "Present"))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("custody");
        assertEmptyOperation(custodyOp, audit.id());
        jdbc.sql(
                        "INSERT INTO audit_expected_requirement(id,organization_id,container_audit_id,requirement_type,specific_asset_id,required_quantity,display_order,snapshot) VALUES(:id,:org,:audit,'SPECIFIC_ASSET',:asset,1,9,'{}')")
                .param("id", UUID.randomUUID())
                .param("org", owner.organizationId())
                .param("audit", audit.id())
                .param("asset", box.id())
                .update();
        var selfOp = UUID.randomUUID();
        assertThatThrownBy(() -> audits.recordFinding(
                        owner, audit.id(), selfOp, AuditFindingType.UNREADABLE_LABEL, box.id(), null))
                .isInstanceOf(ValidationFailedException.class);
        assertEmptyOperation(selfOp, audit.id());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void candidatesHttpAllowsViewerAndAssignedVolunteerButDeniesMutationsAndForeignScope() {
        var f = new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status -> fixture(2, 2));
        jdbc.sql("UPDATE app_user SET password_hash=:hash WHERE id=:id")
                .param("hash", passwords.encode("correct"))
                .param("id", f.owner().userId())
                .update();
        var login = io.kellermann.tarpeisto.security.PermissionTestSupport.login(
                http, f.owner().username(), "correct");
        var path = "/api/v1/audits/" + f.audit().id() + "/manual-candidates";
        assertThat(http.getForEntity(path, String.class).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
        var headers = login.headers();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        jdbc.sql("UPDATE organization_membership SET role='VIEWER' WHERE organization_id=:org AND user_id=:user")
                .param("org", f.owner().organizationId())
                .param("user", f.owner().userId())
                .update();
        assertThat(http.exchange(
                                path,
                                org.springframework.http.HttpMethod.GET,
                                new org.springframework.http.HttpEntity<>(headers),
                                String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
        var body = java.util.Map.of(
                "operationId",
                UUID.randomUUID(),
                "type",
                "UNREADABLE_LABEL",
                "assetId",
                f.units().getFirst().id());
        assertThat(http.postForEntity(
                                "/api/v1/audits/" + f.audit().id() + "/findings",
                                new org.springframework.http.HttpEntity<>(body, headers),
                                String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        jdbc.sql("UPDATE organization_membership SET role='OWNER' WHERE organization_id=:org AND user_id=:user")
                .param("org", f.owner().organizationId())
                .param("user", f.owner().userId())
                .update();
        var invitation = access.create(f.owner(), null, f.audit().batchId());
        var browser = new Browser();
        assertThat(browser.post(
                                "/api/v1/temporary-access/redemptions",
                                java.util.Map.of(
                                        "token",
                                        invitation.token(),
                                        "displayName",
                                        "Named helper",
                                        "operationId",
                                        UUID.randomUUID()))
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
        assertThat(browser.get(path).getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);
        var foreign = new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status -> fixture(1, 1));
        assertThat(browser.get("/api/v1/audits/" + foreign.audit().id() + "/manual-candidates")
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        var noCsrf = browser.headers();
        noCsrf.remove("X-XSRF-TOKEN");
        assertThat(http.postForEntity(
                                "/api/v1/audits/" + f.audit().id() + "/findings",
                                new org.springframework.http.HttpEntity<>(body, noCsrf),
                                String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        access.revoke(f.owner(), invitation.invitation().id());
        assertThat(browser.get(path).getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void manualLabelCannotDuplicateAnObservationInAnotherCurrentBatchAudit() {
        var owner = owner();
        var parent = box(owner);
        var first = box(owner);
        var second = box(owner);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            assetRepository.findById(first.id()).orElseThrow().moveTo(null, parent.id(), clock.instant());
            assetRepository.findById(second.id()).orElseThrow().moveTo(null, parent.id(), clock.instant());
        });
        var model = models.create(
                owner, "Shared cable", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, false);
        var unit = assets.create(owner, model.id(), null, null, List.of());
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status ->
                        assetRepository.findById(unit.id()).orElseThrow().moveTo(null, first.id(), clock.instant()));
        packing.add(owner, first.id(), PackingRequirementType.MODEL_QUANTITY, model.id(), null, BigDecimal.ONE);
        packing.add(owner, second.id(), PackingRequirementType.SPECIFIC_ASSET, null, unit.publicCode(), BigDecimal.ONE);
        var root = audits.launchContainerAudit(owner, parent.id(), parent.publicCode(), UUID.randomUUID());
        var allTasks = tasks.findAllByOrganizationIdAndAuditBatchIdOrderById(owner.organizationId(), root.batchId());
        var a = audits.start(
                owner,
                allTasks.stream()
                        .filter(task -> task.getContainerAssetId().equals(first.id()))
                        .findFirst()
                        .orElseThrow()
                        .getId(),
                first.publicCode());
        var b = audits.start(
                owner,
                allTasks.stream()
                        .filter(task -> task.getContainerAssetId().equals(second.id()))
                        .findFirst()
                        .orElseThrow()
                        .getId(),
                second.publicCode());
        var recorded = audits.scan(owner, a.id(), UUID.randomUUID(), unit.publicCode());
        assertThat(recorded.scans()).hasSize(1);
        var op = UUID.randomUUID();
        assertThatThrownBy(() -> audits.recordFinding(
                        owner, b.id(), op, AuditFindingType.UNREADABLE_LABEL, unit.id(), "Missing label"))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("another audit");
        assertEmptyOperation(op, b.id());
        assertThat(audits.get(owner, a.taskId()).scans()).hasSize(1);
    }

    private void assertEmptyOperation(UUID op, UUID audit) {
        assertThat(jdbc.sql("SELECT count(*) FROM audit_operation WHERE client_operation_id=:id")
                        .param("id", op)
                        .query(Integer.class)
                        .single())
                .isZero();
        var state = audits.get(
                auditOwner(audit),
                jdbc.sql("SELECT audit_task_id FROM container_audit WHERE id=:id")
                        .param("id", audit)
                        .query(UUID.class)
                        .single());
        assertThat(state.scans()).isEmpty();
        assertThat(state.findings()).isEmpty();
    }

    private TarpeistoPrincipal auditOwner(UUID audit) {
        var org = jdbc.sql("SELECT organization_id FROM container_audit WHERE id=:id")
                .param("id", audit)
                .query(UUID.class)
                .single();
        var id = jdbc.sql("SELECT user_id FROM organization_membership WHERE organization_id=:org AND role='OWNER'")
                .param("org", org)
                .query(UUID.class)
                .single();
        var u = users.findById(id).orElseThrow();
        return new TarpeistoPrincipal(id, u.getUsername(), u.getDisplayName(), org, OrganizationRole.OWNER);
    }

    private final class Browser {
        private final java.util.Map<String, String> cookies = new java.util.LinkedHashMap<>();

        Browser() {
            absorb(http.getForEntity("/api/v1/application", String.class));
        }

        org.springframework.http.HttpHeaders headers() {
            var h = new org.springframework.http.HttpHeaders();
            h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            h.set(
                    org.springframework.http.HttpHeaders.COOKIE,
                    cookies.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(java.util.stream.Collectors.joining("; ")));
            if (cookies.containsKey("XSRF-TOKEN")) h.set("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            return h;
        }

        org.springframework.http.ResponseEntity<String> get(String path) {
            var response = http.exchange(
                    path,
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers()),
                    String.class);
            absorb(response);
            return response;
        }

        org.springframework.http.ResponseEntity<String> post(String path, Object body) {
            var response =
                    http.postForEntity(path, new org.springframework.http.HttpEntity<>(body, headers()), String.class);
            absorb(response);
            return response;
        }

        void absorb(org.springframework.http.ResponseEntity<?> response) {
            for (var cookie : response.getHeaders().getOrEmpty(org.springframework.http.HttpHeaders.SET_COOKIE)) {
                var first = cookie.split(";", 2)[0];
                var pair = first.split("=", 2);
                cookies.put(pair[0], pair.length > 1 ? pair[1] : "");
            }
        }
    }

    private Fixture fixture(int count, int required) {
        var owner = owner();
        var box = box(owner);
        var model = models.create(owner, "Cable", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, false);
        var units = assets.createBulk(owner, model.id(), count, null);
        for (var unit : units) {
            var entity = assetRepository.findById(unit.id()).orElseThrow();
            entity.moveTo(null, box.id(), clock.instant());
        }
        entityManager.flush();
        packing.add(
                owner, box.id(), PackingRequirementType.MODEL_QUANTITY, model.id(), null, BigDecimal.valueOf(required));
        var audit = audits.launchContainerAudit(owner, box.id(), box.publicCode(), UUID.randomUUID());
        entityManager.flush();
        return new Fixture(owner, box, units, audit);
    }

    private AssetView box(TarpeistoPrincipal owner) {
        var model = models.create(
                owner, "Case " + UUID.randomUUID(), null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, true);
        return assets.create(owner, model.id(), "Scanner case", null, List.of());
    }

    private TarpeistoPrincipal owner() {
        return member(
                organizations
                        .ensureOrganizationExists("Scanner " + UUID.randomUUID())
                        .getId(),
                OrganizationRole.OWNER);
    }

    private TarpeistoPrincipal member(UUID org, OrganizationRole role) {
        var user = users.saveAndFlush(new User(
                UUID.randomUUID(),
                "scanner-" + UUID.randomUUID(),
                null,
                "Scanner owner",
                "{noop}unused",
                true,
                clock.instant()));
        memberships.saveAndFlush(
                new OrganizationMembership(UUID.randomUUID(), org, user.getId(), role, clock.instant()));
        return new TarpeistoPrincipal(user.getId(), user.getUsername(), user.getDisplayName(), org, role);
    }

    private record Fixture(TarpeistoPrincipal owner, AssetView box, List<AssetView> units, ContainerAuditView audit) {}
}

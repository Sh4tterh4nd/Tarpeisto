package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.model.BookingLineType;
import io.kellermann.tarpeisto.model.DashboardQueue;
import io.kellermann.tarpeisto.model.LifecycleState;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.CheckoutManifestAssetRepository;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.security.TemporaryAccessContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

@Import(WorkboardAvailabilityIntegrationTests.FixedClock.class)
@Transactional
class WorkboardAvailabilityIntegrationTests extends AbstractIntegrationTest {
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
    private BookingService bookings;

    @Autowired
    private BookingReservationService reservations;

    @Autowired
    private AuditService audits;

    @Autowired
    private ReviewService review;

    @Autowired
    private CheckoutService checkout;

    @Autowired
    private CheckoutManifestAssetRepository manifestAssets;

    @Autowired
    private DashboardService dashboard;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    @Test
    void completeMixedCablesExplainOwnAuditWithoutDerivativeShortagesAndClearOnlyAfterCompletion() {
        var fixture = mixed(owner(), 20);
        assertThat(row(fixture)).isNull();
        var audit = audits.launchContainerAudit(
                fixture.owner(), fixture.box().id(), fixture.box().publicCode(), UUID.randomUUID());
        assertOperationalExplanation(fixture, "pending audit");
        var event = event(fixture.owner(), fixture.box().id(), BookingLineType.CONTAINER);
        var full = reservations.preview(fixture.owner(), event.id());
        assertThat(full.reservable()).isFalse();
        assertThat(full.conflicts())
                .filteredOn(c -> c.type().equals("MODEL_CAPACITY"))
                .hasSize(4);
        assertThat(full.conflicts())
                .filteredOn(c -> c.type().equals("ASSET_UNAVAILABLE"))
                .allSatisfy(c -> assertThat(c.message())
                        .contains("Asset ", "pending audit")
                        .doesNotContain("inactive"));
        assertThat(reservations
                        .reserve(
                                fixture.owner(),
                                event.id(),
                                bookings.get(fixture.owner(), event.id()).version())
                        .reservable())
                .isFalse();
        complete(fixture, audit.id());
        assertThat(reservations
                        .previewContainer(fixture.owner(), fixture.box().id())
                        .reservable())
                .isTrue();
        assertThat(row(fixture)).isNull();
    }

    @Test
    void completedChildStillExplainsPendingContainingAuditWithoutChangingTheGuard() {
        var fixture = mixed(owner(), 20);
        var parent = assets.create(fixture.owner(), fixture.box().assetModelId(), "Outer case", null, List.of());
        move(fixture.box().id(), parent.id());
        var pending = audits.launchContainerAudit(fixture.owner(), parent.id(), parent.publicCode(), UUID.randomUUID());
        var child =
                audits.start(fixture.owner(), pending.taskId(), fixture.box().publicCode());
        complete(fixture, child.id());
        assertOperationalExplanation(fixture, "containing container has a pending audit");
    }

    @Test
    void openRepairExplainsAvailabilityAndDisappearsAfterExplicitRepairClosure() {
        var fixture = mixed(owner(), 20);
        var repair =
                review.openRepair(fixture.owner(), fixture.units().getFirst().id(), null, "Inspect connector");
        assertOperationalExplanation(fixture, "open repair");
        review.closeRepair(fixture.owner(), repair.id(), io.kellermann.tarpeisto.model.Condition.GOOD);
        assertThat(reservations
                        .previewContainer(fixture.owner(), fixture.box().id())
                        .reservable())
                .isTrue();
        assertThat(row(fixture)).isNull();
    }

    @Test
    void unreleasedManifestCustodyIsAccurateAndNeverReleasedByDiagnosis() {
        var fixture = mixed(owner(), 20);
        var event = event(fixture.owner(), fixture.box().id(), BookingLineType.CONTAINER);
        assertThat(reservations
                        .reserve(
                                fixture.owner(),
                                event.id(),
                                bookings.get(fixture.owner(), event.id()).version())
                        .reservable())
                .isTrue();
        var manifest = checkout.checkout(
                fixture.owner(),
                event.id(),
                bookings.get(fixture.owner(), event.id()).version(),
                UUID.randomUUID(),
                null,
                List.of());
        entityManager.flush();
        assertOperationalExplanation(fixture, "unreleased checkout custody");
        assertThat(checkout.get(fixture.owner(), event.id()).assets())
                .hasSize(manifest.assets().size());
        assertThat(reservations
                        .previewContainer(fixture.owner(), fixture.box().id())
                        .reservable())
                .isFalse();
        for (var asset : manifest.assets())
            assertThat(manifestAssets.existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                            fixture.owner().organizationId(), asset.assetId()))
                    .isTrue();
    }

    @Test
    void realShortageWithSameModelInactiveExtraSurvivesOperationalSuppression() {
        var fixture = mixed(owner(), 21);
        var shortage =
                reservations.previewContainer(fixture.owner(), fixture.box().id());
        assertThat(shortage.reservable()).isFalse();
        assertThat(shortage.conflicts())
                .anyMatch(c -> c.type().equals("MODEL_CAPACITY")
                        && c.modelId().equals(fixture.units().getFirst().assetModelId()));
        var extra = assets.create(
                fixture.owner(), fixture.units().getFirst().assetModelId(), "Retired spare", null, List.of());
        move(extra.id(), fixture.box().id());
        var entity = assetRepository.findById(extra.id()).orElseThrow();
        entity.changeLifecycleState(LifecycleState.RETIRED, clock.instant());
        assetRepository.saveAndFlush(entity);
        audits.launchContainerAudit(
                fixture.owner(), fixture.box().id(), fixture.box().publicCode(), UUID.randomUUID());
        var result =
                reservations.previewContainer(fixture.owner(), fixture.box().id());
        assertThat(result.reservable()).isFalse();
        assertThat(result.conflicts()).anySatisfy(c -> {
            assertThat(c.type()).isEqualTo("MODEL_CAPACITY");
            assertThat(c.modelId()).isEqualTo(fixture.units().getFirst().assetModelId());
            assertThat(c.requiredQuantity()).isEqualByComparingTo("21");
        });
        assertThat(result.conflicts()).anySatisfy(c -> assertThat(c.message()).contains("inactive lifecycle RETIRED"));
    }

    @Test
    void overlappingReservationDeficitRemainsEvenWhenExcludedCandidatesCouldCoverTheNumericalGap() {
        var fixture = mixed(owner(), 20);
        var spare = assets.create(
                fixture.owner(), fixture.units().getFirst().assetModelId(), "External spare", null, List.of());
        var reserved = event(fixture.owner(), spare.id(), BookingLineType.ASSET);
        assertThat(reservations
                        .reserve(
                                fixture.owner(),
                                reserved.id(),
                                bookings.get(fixture.owner(), reserved.id()).version())
                        .reservable())
                .isTrue();
        audits.launchContainerAudit(
                fixture.owner(), fixture.box().id(), fixture.box().publicCode(), UUID.randomUUID());
        var result =
                reservations.previewContainer(fixture.owner(), fixture.box().id());
        assertThat(result.conflicts()).anySatisfy(c -> {
            assertThat(c.type()).isEqualTo("MODEL_CAPACITY");
            assertThat(c.modelId()).isEqualTo(spare.assetModelId());
            assertThat(c.requiredQuantity()).isEqualByComparingTo("21");
            assertThat(c.availableQuantity()).isEqualByComparingTo("1");
        });
        assertThat(result.reservable()).isFalse();
    }

    @Test
    void exactPinElsewhereCannotCountAsAnOtherwiseEligibleOperationalCandidate() {
        var fixture = mixed(owner(), 20);
        var pinned = fixture.units().getFirst();
        var otherBox =
                assets.create(fixture.owner(), fixture.box().assetModelId(), "Exact destination", null, List.of());
        packing.add(
                fixture.owner(),
                otherBox.id(),
                PackingRequirementType.SPECIFIC_ASSET,
                null,
                pinned.id().toString(),
                BigDecimal.ONE);
        audits.launchContainerAudit(
                fixture.owner(), fixture.box().id(), fixture.box().publicCode(), UUID.randomUUID());
        entityManager.flush();
        var result =
                reservations.previewContainer(fixture.owner(), fixture.box().id());
        assertThat(result.reservable()).isFalse();
        assertThat(result.conflicts()).anySatisfy(c -> {
            assertThat(c.type()).isEqualTo("MODEL_CAPACITY");
            assertThat(c.modelId()).isEqualTo(pinned.assetModelId());
            assertThat(c.requiredQuantity()).isEqualByComparingTo("20");
            assertThat(c.availableQuantity()).isEqualByComparingTo("0");
        });
        assertThat(assetRepository.findById(pinned.id()).orElseThrow().getParentContainerAssetId())
                .isEqualTo(fixture.box().id());
    }

    @Test
    void lifecycleAndArchivedModelRemainHonestNonOperationalExclusions() {
        var fixture = mixed(owner(), 20);
        var entity = assetRepository.findById(fixture.units().getFirst().id()).orElseThrow();
        entity.changeLifecycleState(LifecycleState.LOST, clock.instant());
        assetRepository.saveAndFlush(entity);
        var inactive =
                reservations.previewContainer(fixture.owner(), fixture.box().id());
        assertThat(inactive.conflicts()).anySatisfy(c -> assertThat(c.message()).contains("inactive lifecycle LOST"));
        assertThat(inactive.reservable()).isFalse();
        models.archive(fixture.owner(), fixture.units().getLast().assetModelId());
        var archived =
                reservations.previewContainer(fixture.owner(), fixture.box().id());
        assertThat(archived.conflicts()).anySatisfy(c -> assertThat(c.message()).contains("model is archived"));
        assertThat(archived.reservable()).isFalse();
    }

    @Test
    void readRolesAndTenantScopeRemainPermanentAndIsolated() {
        var fixture = mixed(owner(), 20);
        for (var role : io.kellermann.tarpeisto.model.OrganizationRole.values()) {
            var principal = new TarpeistoPrincipal(
                    fixture.owner().userId(),
                    fixture.owner().username(),
                    "Reader",
                    fixture.owner().organizationId(),
                    role);
            assertThat(reservations
                            .previewContainer(principal, fixture.box().id())
                            .reservable())
                    .isTrue();
        }
        var other = owner();
        var foreign = reservations.previewContainer(other, fixture.box().id());
        assertThat(foreign.reservable()).isFalse();
        assertThat(foreign.conflicts())
                .allSatisfy(c -> assertThat(c.message())
                        .doesNotContain("LAN", fixture.box().publicCode()));
        assertThat(dashboard.page(other, DashboardQueue.CONTAINERS, 100, null).items())
                .isEmpty();
        var temporary = new TarpeistoPrincipal(
                fixture.owner().userId(),
                "Volunteer",
                "Volunteer",
                fixture.owner().organizationId(),
                OrganizationRole.OWNER,
                new TemporaryAccessContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        clock.instant().plusSeconds(3600)));
        assertThatThrownBy(() ->
                        reservations.previewContainer(temporary, fixture.box().id()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> dashboard.page(temporary, DashboardQueue.CONTAINERS, 100, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(
                        () -> reservations.previewContainer(null, fixture.box().id()))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void assertOperationalExplanation(Fixture fixture, String cause) {
        entityManager.flush();
        var result =
                reservations.previewContainer(fixture.owner(), fixture.box().id());
        assertThat(result.reservable()).isFalse();
        assertThat(result.conflicts()).noneMatch(c -> c.type().equals("MODEL_CAPACITY"));
        assertThat(result.conflicts())
                .allSatisfy(c -> assertThat(c.message()).contains(cause).doesNotContain("inactive"));
        var row = row(fixture);
        assertThat(row).isNotNull();
        assertThat(row.state()).isEqualTo("UNAVAILABLE");
        assertThat(row.reason())
                .contains("Packing is complete; availability is blocked", cause)
                .doesNotContain("inactive", "Insufficient eligible capacity");
        assertThat(row.reason().split(java.util.regex.Pattern.quote(cause), -1)).hasSize(2);
    }

    private void complete(Fixture fixture, UUID auditId) {
        for (var unit : fixture.units()) audits.scan(fixture.owner(), auditId, UUID.randomUUID(), unit.publicCode());
        audits.complete(
                fixture.owner(), auditId, UUID.randomUUID(), fixture.box().publicCode(), false, false);
        entityManager.flush();
    }

    private DashboardService.Row row(Fixture fixture) {
        entityManager.flush();
        return dashboard.page(fixture.owner(), DashboardQueue.CONTAINERS, 100, null).items().stream()
                .filter(r -> r.id().equals(fixture.box().id()))
                .findFirst()
                .orElse(null);
    }

    private Fixture mixed(TarpeistoPrincipal owner, int required20m) {
        var box = assets.create(owner, model(owner, "RAKO", true).id(), "ARRSZG equivalent", null, List.of());
        var units = new ArrayList<AssetView>();
        for (int length : List.of(20, 30, 50)) {
            var model = model(owner, "LAN " + length + "m", false);
            var batch = assets.createBulk(owner, model.id(), length == 20 ? 20 : 1, null);
            for (var unit : batch) move(unit.id(), box.id());
            units.addAll(batch);
            packing.add(
                    owner,
                    box.id(),
                    PackingRequirementType.MODEL_QUANTITY,
                    model.id(),
                    null,
                    BigDecimal.valueOf(length == 20 ? required20m : 1));
        }
        entityManager.flush();
        return new Fixture(owner, box, List.copyOf(units));
    }

    private AssetModelView model(TarpeistoPrincipal owner, String name, boolean container) {
        return models.create(owner, name, null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, container);
    }

    private void move(UUID assetId, UUID parentId) {
        var asset = assetRepository.findById(assetId).orElseThrow();
        asset.moveTo(null, parentId, clock.instant());
        assetRepository.saveAndFlush(asset);
    }

    private BookingView event(TarpeistoPrincipal owner, UUID assetId, BookingLineType type) {
        var booking = bookings.create(
                owner,
                UUID.randomUUID(),
                "Current event",
                null,
                null,
                null,
                clock.instant().minusSeconds(30),
                clock.instant().plusSeconds(3600));
        bookings.addLine(owner, booking.id(), booking.version(), type, assetId, null, BigDecimal.ONE);
        return bookings.get(owner, booking.id());
    }

    private TarpeistoPrincipal owner() {
        var org = organizations.ensureOrganizationExists("Diagnosis " + UUID.randomUUID());
        var user = users.saveAndFlush(new User(
                UUID.randomUUID(),
                "diagnosis-" + UUID.randomUUID(),
                null,
                "Owner",
                "{noop}unused",
                true,
                clock.instant()));
        memberships.saveAndFlush(new OrganizationMembership(
                UUID.randomUUID(), org.getId(), user.getId(), OrganizationRole.OWNER, clock.instant()));
        return new TarpeistoPrincipal(
                user.getId(), user.getUsername(), user.getDisplayName(), org.getId(), OrganizationRole.OWNER);
    }

    private record Fixture(TarpeistoPrincipal owner, AssetView box, List<AssetView> units) {}

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock diagnosisClock() {
            return Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}

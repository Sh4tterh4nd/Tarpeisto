package io.kellermann.bigcontainers.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.bigcontainers.AbstractIntegrationTest;
import io.kellermann.bigcontainers.model.Organization;
import io.kellermann.bigcontainers.model.OrganizationMembership;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.model.User;
import io.kellermann.bigcontainers.repository.OrganizationMembershipRepository;
import io.kellermann.bigcontainers.repository.UserRepository;
import io.kellermann.bigcontainers.security.PermissionTestSupport;
import io.kellermann.bigcontainers.security.PermissionTestSupport.AuthenticatedSession;
import io.kellermann.bigcontainers.service.OrganizationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL/MVC acceptance coverage for Phase 7 booking reservations and history. */
class BookingControllerIntegrationTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBattery1";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private UserRepository users;

    @Autowired
    private OrganizationMembershipRepository memberships;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @ParameterizedTest
    @EnumSource(OrganizationRole.class)
    void everyAuthenticatedRoleCanReadBookingsAndPreviews(OrganizationRole role) {
        Fixture fixture = fixture();
        UUID booking = createBooking(fixture, "Read-only booking", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2));

        assertThat(exchange(fixture.session(role), HttpMethod.GET, "/api/v1/bookings", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(fixture.session(role), HttpMethod.GET, "/api/v1/bookings/" + booking, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(preview(fixture.session(role), booking).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @EnumSource(
            value = OrganizationRole.class,
            names = {"OPERATOR_AUDITOR", "VIEWER"})
    void onlyOwnersAndDeputiesCanMutateBookings(OrganizationRole role) {
        Fixture fixture = fixture();
        UUID booking = createBooking(fixture, "Restricted booking", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2));
        long version = bookingVersion(fixture.owner(), booking);

        assertThat(createBookingResponse(
                                fixture.session(role), "Forbidden", LocalDate.of(2026, 5, 3), LocalDate.of(2026, 5, 4))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(addAssetLine(fixture.session(role), booking, version, createAsset(fixture, "Forbidden asset"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reserve(fixture.session(role), booking, version).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cancel(fixture.session(role), booking, version).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unauthenticatedCallsAreRejectedAndCsrfIsRequiredForMutations() {
        assertThat(restTemplate.getForEntity("/api/v1/bookings", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate
                        .postForEntity("/api/v1/bookings", Map.of(), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        Fixture fixture = fixture();
        HttpHeaders headers = fixture.owner().headers();
        headers.remove("X-XSRF-TOKEN");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/bookings",
                HttpMethod.POST,
                new HttpEntity<>(
                        bookingBody("Missing CSRF", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2)), headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void crossOrganizationBookingsAppearMissing() {
        Fixture one = fixture();
        Fixture two = fixture();
        UUID booking = createBooking(one, "Private booking", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2));

        assertThat(exchange(two.owner(), HttpMethod.GET, "/api/v1/bookings/" + booking, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reserve(two.owner(), booking, 0).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void draftsDoNotBlockWhileReservedContainersBlockAncestorDescendantAndIndividualRequests() {
        Fixture fixture = fixture();
        UUID containerModel = createSerializedModel(fixture, "Flightcase", true);
        UUID root = createAsset(fixture, containerModel, "Root case");
        UUID child = createAsset(fixture, containerModel, "Child case");
        UUID cable = createAsset(fixture, createSerializedModel(fixture, "Cable", false), "Network cable");
        place(fixture, child, root);
        place(fixture, cable, child);

        UUID draft = createBooking(fixture, "Unheld draft", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3));
        addContainerLine(fixture.owner(), draft, bookingVersion(fixture.owner(), draft), root);
        UUID freeIndividual = createBooking(fixture, "Still free", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3));
        addAssetLine(fixture.owner(), freeIndividual, bookingVersion(fixture.owner(), freeIndividual), cable);
        assertThat(json(preview(fixture.owner(), freeIndividual).getBody())
                        .path("reservable")
                        .asBoolean())
                .isTrue();

        UUID reserved = createBooking(fixture, "Reserved child", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3));
        addContainerLine(fixture.owner(), reserved, bookingVersion(fixture.owner(), reserved), child);
        assertThat(json(reserve(fixture.owner(), reserved, bookingVersion(fixture.owner(), reserved))
                                .getBody())
                        .path("reservable")
                        .asBoolean())
                .isTrue();

        UUID ancestor = createBooking(fixture, "Ancestor overlap", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3));
        addContainerLine(fixture.owner(), ancestor, bookingVersion(fixture.owner(), ancestor), root);
        UUID descendant =
                createBooking(fixture, "Descendant overlap", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3));
        addAssetLine(fixture.owner(), descendant, bookingVersion(fixture.owner(), descendant), cable);

        assertAssetOverlap(preview(fixture.owner(), ancestor), child);
        assertAssetOverlap(preview(fixture.owner(), descendant), cable);
    }

    @Test
    void anExactRequirementOutsideItsContainerIsClaimedAndAdjacentDatesDoNotOverlap() {
        Fixture fixture = fixture();
        UUID container = createAsset(fixture, createSerializedModel(fixture, "Case", true), "Case");
        UUID exact = createAsset(fixture, createSerializedModel(fixture, "AP", false), "Exact AP");
        addExactRequirement(fixture, container, exact);

        UUID containerBooking = createBooking(fixture, "Container", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2));
        addContainerLine(
                fixture.owner(), containerBooking, bookingVersion(fixture.owner(), containerBooking), container);
        reserve(fixture.owner(), containerBooking, bookingVersion(fixture.owner(), containerBooking));

        UUID overlapping = createBooking(fixture, "Exact overlap", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2));
        addAssetLine(fixture.owner(), overlapping, bookingVersion(fixture.owner(), overlapping), exact);
        assertAssetOverlap(preview(fixture.owner(), overlapping), exact);

        UUID adjacent = createBooking(fixture, "Exact adjacent", LocalDate.of(2026, 5, 2), LocalDate.of(2026, 5, 3));
        addAssetLine(fixture.owner(), adjacent, bookingVersion(fixture.owner(), adjacent), exact);
        assertThat(json(preview(fixture.owner(), adjacent).getBody())
                        .path("reservable")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void consumableAtpIsGlobalEvenWhenEventDatesDoNotOverlap() {
        Fixture fixture = fixture();
        UUID source = createAsset(fixture, createSerializedModel(fixture, "Stock case", true), "Stock case");
        UUID consumableModel = createQuantityModel(fixture, "Gaffer tape");
        UUID stock = receive(fixture, consumableModel, source, "10");

        UUID first = createBooking(fixture, "First tape", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2));
        addConsumableLine(fixture.owner(), first, bookingVersion(fixture.owner(), first), stock, "6");
        reserve(fixture.owner(), first, bookingVersion(fixture.owner(), first));
        UUID second = createBooking(fixture, "Later tape", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2));
        addConsumableLine(fixture.owner(), second, bookingVersion(fixture.owner(), second), stock, "5");

        JsonNode preview = json(preview(fixture.owner(), second).getBody());
        assertThat(preview.path("reservable").asBoolean()).isFalse();
        assertThat(preview.path("conflicts").get(0).path("type").asText()).isEqualTo("CONSUMABLE_ATP");
    }

    @Test
    void cancellationReleasesHoldsButReservationRowsAndActivityRemainImmutable() {
        Fixture fixture = fixture();
        UUID asset = createAsset(fixture, "Cancellation asset");
        UUID first = createBooking(fixture, "First", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3));
        addAssetLine(fixture.owner(), first, bookingVersion(fixture.owner(), first), asset);
        reserve(fixture.owner(), first, bookingVersion(fixture.owner(), first));
        UUID reservationRevision = jdbc.sql(
                        "SELECT id FROM event_booking_reservation_revision WHERE event_booking_id = :booking")
                .param("booking", first)
                .query(UUID.class)
                .single();
        UUID claim = jdbc.sql(
                        "SELECT id FROM event_booking_reservation_claim WHERE reservation_revision_id = :revision")
                .param("revision", reservationRevision)
                .query(UUID.class)
                .single();

        cancel(fixture.owner(), first, bookingVersion(fixture.owner(), first));
        assertThat(jdbc.sql("SELECT count(*) FROM event_booking_reservation_revision WHERE event_booking_id = :booking")
                        .param("booking", first)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE event_booking_reservation_revision SET action = 'CANCELLED' WHERE id = :id")
                        .param("id", reservationRevision)
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM event_booking_reservation_claim WHERE id = :id")
                        .param("id", claim)
                        .update())
                .isInstanceOf(DataAccessException.class);

        UUID replacement = createBooking(fixture, "Released", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3));
        addAssetLine(fixture.owner(), replacement, bookingVersion(fixture.owner(), replacement), asset);
        assertThat(json(preview(fixture.owner(), replacement).getBody())
                        .path("reservable")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void staleBookingVersionsReturnConflict() {
        Fixture fixture = fixture();
        UUID booking = createBooking(fixture, "Versioned", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2));
        UUID first = createAsset(fixture, "First versioned asset");
        UUID second = createAsset(fixture, "Second versioned asset");
        long version = bookingVersion(fixture.owner(), booking);
        ResponseEntity<String> added = addAssetLine(fixture.owner(), booking, version, first);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID line = UUID.fromString(
                json(added.getBody()).path("lines").get(0).path("id").asText());
        assertThat(addAssetLine(fixture.owner(), booking, version, second).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(removeLine(fixture.owner(), booking, line, version).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(reserve(fixture.owner(), booking, version).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void concurrentReservationsOfTheSameExactAssetLeaveOneReservedAndOneDraft() throws Exception {
        Fixture fixture = fixture();
        UUID asset = createAsset(fixture, "Concurrent asset");
        UUID first = bookingWithAsset(fixture, "Concurrent first", asset);
        UUID second = bookingWithAsset(fixture, "Concurrent second", asset);
        long firstVersion = bookingVersion(fixture.owner(), first);
        long secondVersion = bookingVersion(fixture.deputy(), second);

        ResponseEntity<String>[] results = runTogether(
                () -> reserve(fixture.owner(), first, firstVersion),
                () -> reserve(fixture.deputy(), second, secondVersion));
        assertThat(results)
                .allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));
        long reserved = jdbc.sql(
                        "SELECT count(*) FROM event_booking WHERE id IN (:first, :second) AND status = 'RESERVED'")
                .param("first", first)
                .param("second", second)
                .query(Long.class)
                .single();
        assertThat(reserved).isEqualTo(1);
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM event_booking_reservation_revision WHERE event_booking_id IN (:first, :second)")
                        .param("first", first)
                        .param("second", second)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aFailedReservationDoesNotWriteReservationSnapshotsOrActivity() {
        Fixture fixture = fixture();
        UUID asset = createAsset(fixture, "Rollback asset");
        UUID held = bookingWithAsset(fixture, "Held", asset);
        reserve(fixture.owner(), held, bookingVersion(fixture.owner(), held));
        UUID rejected = bookingWithAsset(fixture, "Rejected", asset);

        ResponseEntity<String> response = reserve(fixture.owner(), rejected, bookingVersion(fixture.owner(), rejected));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response.getBody()).path("reservable").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT status FROM event_booking WHERE id = :id")
                        .param("id", rejected)
                        .query(String.class)
                        .single())
                .isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT count(*) FROM event_booking_reservation_revision WHERE event_booking_id = :id")
                        .param("id", rejected)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM activity_log WHERE target_type = 'BOOKING' AND target_id = :id")
                        .param("id", rejected)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void fullCurrentContentsSatisfyModelDemandWithoutDoubleCounting() {
        Fixture f = fixture();
        UUID model = createSerializedModel(f, "Cable", false);
        UUID box = createAsset(f, createSerializedModel(f, "Case", true), "Case");
        place(f, createAsset(f, model, "Cable one"), box);
        place(f, createAsset(f, model, "Cable two"), box);
        requirement(f, box, model, "MODEL_QUANTITY", "2");
        UUID b = createBooking(f, "Complete box", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addContainerLine(f.owner(), b, bookingVersion(f.owner(), b), box);
        assertThat(json(reserve(f.owner(), b, bookingVersion(f.owner(), b)).getBody())
                        .path("reservable")
                        .asBoolean())
                .isTrue();
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM event_booking_reservation_claim WHERE claim_type='FLEXIBLE_ASSET' AND reservation_revision_id=(SELECT current_revision_id FROM event_booking WHERE id=:id)")
                        .param("id", b)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void individualBookingsRespectHeldCapacityAndPinnedAssetsCannotSupplyAnotherBox() {
        Fixture f = fixture();
        UUID model = createSerializedModel(f, "AP", false);
        UUID ap = createAsset(f, model, "AP");
        UUID box = createAsset(f, createSerializedModel(f, "Case", true), "Case");
        requirement(f, box, model, "MODEL_QUANTITY", "1");
        UUID held = createBooking(f, "Capacity held", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addContainerLine(f.owner(), held, bookingVersion(f.owner(), held), box);
        assertThat(json(reserve(f.owner(), held, bookingVersion(f.owner(), held))
                                .getBody())
                        .path("reservable")
                        .asBoolean())
                .isTrue();
        UUID individual = createBooking(f, "Individual", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addAssetLine(f.owner(), individual, bookingVersion(f.owner(), individual), ap);
        assertConflict(preview(f.owner(), individual), "MODEL_CAPACITY");
        cancel(f.owner(), held, bookingVersion(f.owner(), held));
        UUID otherBox = createAsset(f, createSerializedModel(f, "Pinned case", true), "Pinned case");
        addExactRequirement(f, otherBox, ap);
        assertConflict(preview(f.owner(), held), "MODEL_CAPACITY");
    }

    @Test
    void anIndividuallyHeldInterchangeableCableCanBeReplacedBySpareCapacity() {
        Fixture f = fixture();
        UUID model = createSerializedModel(f, "Cable", false);
        UUID box = createAsset(f, createSerializedModel(f, "Case", true), "Case");
        UUID first = createAsset(f, model, "First");
        UUID second = createAsset(f, model, "Second");
        createAsset(f, model, "Spare");
        place(f, first, box);
        place(f, second, box);
        requirement(f, box, model, "MODEL_QUANTITY", "2");
        UUID individual = createBooking(f, "Individual", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addAssetLine(f.owner(), individual, bookingVersion(f.owner(), individual), first);
        reserve(f.owner(), individual, bookingVersion(f.owner(), individual));
        UUID whole = createBooking(f, "Whole box", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addContainerLine(f.owner(), whole, bookingVersion(f.owner(), whole), box);
        assertThat(json(reserve(f.owner(), whole, bookingVersion(f.owner(), whole))
                                .getBody())
                        .path("reservable")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void capacityUsesPeakRatherThanSumOfDisjointOverlappingEvents() {
        Fixture f = fixture();
        UUID model = createSerializedModel(f, "Cable", false);
        createAsset(f, model, "First");
        createAsset(f, model, "Second");
        UUID cases = createSerializedModel(f, "Case", true);
        UUID one = capacityBooking(f, cases, model, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        UUID two = capacityBooking(f, cases, model, LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 3));
        reserve(f.owner(), one, bookingVersion(f.owner(), one));
        reserve(f.owner(), two, bookingVersion(f.owner(), two));
        UUID spanning = capacityBooking(f, cases, model, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3));
        assertThat(json(reserve(f.owner(), spanning, bookingVersion(f.owner(), spanning))
                                .getBody())
                        .path("reservable")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void stockSourcesInsideReservedContainersAreUnavailableAndOwnCarriedStockCannotBeIssuedTwice() {
        Fixture f = fixture();
        UUID box = createAsset(f, createSerializedModel(f, "Stock case", true), "Stock case");
        UUID model = createQuantityModel(f, "Tape");
        UUID stock = receive(f, model, box, "10");
        requirement(f, box, model, "CONSUMABLE_QUANTITY", "2");
        UUID held = createBooking(f, "Carried stock", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addContainerLine(f.owner(), held, bookingVersion(f.owner(), held), box);
        reserve(f.owner(), held, bookingVersion(f.owner(), held));
        assertThat(jdbc.sql(
                                "SELECT quantity FROM event_booking_reservation_claim WHERE claim_type='CARRIED_CONSUMABLE' AND reservation_revision_id=(SELECT current_revision_id FROM event_booking WHERE id=:id)")
                        .param("id", held)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("10");
        UUID issue = createBooking(f, "Separate issue", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addConsumableLine(f.owner(), issue, bookingVersion(f.owner(), issue), stock, "1");
        assertConflict(preview(f.owner(), issue), "SOURCE_CONTAINER_UNAVAILABLE");
        cancel(f.owner(), held, bookingVersion(f.owner(), held));
        UUID duplicate = createBooking(f, "Duplicate", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addContainerLine(f.owner(), duplicate, bookingVersion(f.owner(), duplicate), box);
        addConsumableLine(f.owner(), duplicate, bookingVersion(f.owner(), duplicate), stock, "1");
        assertConflict(preview(f.owner(), duplicate), "CONSUMABLE_ALREADY_CARRIED");
    }

    @Test
    void exactRequiredExternalContainerExpandsItsChildrenAndContainerIndividualVariantIsRejected() {
        Fixture f = fixture();
        UUID model = createSerializedModel(f, "Case", true);
        UUID outer = createAsset(f, model, "Outer");
        UUID external = createAsset(f, model, "External");
        UUID child = createAsset(f, "External child");
        place(f, child, external);
        addExactRequirement(f, outer, external);
        UUID b = createBooking(f, "Outer booking", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        assertThat(addAssetLine(f.owner(), b, bookingVersion(f.owner(), b), outer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        addContainerLine(f.owner(), b, bookingVersion(f.owner(), b), outer);
        reserve(f.owner(), b, bookingVersion(f.owner(), b));
        UUID other = createBooking(f, "Child overlap", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addAssetLine(f.owner(), other, bookingVersion(f.owner(), other), child);
        assertAssetOverlap(preview(f.owner(), other), child);
    }

    @Test
    void packingAdditionRecalculatesFutureHoldsAndRemovalRequiresAffectedBookingConfirmation() {
        Fixture f = fixture();
        UUID box = createAsset(f, createSerializedModel(f, "Case", true), "Case");
        UUID exact = createAsset(f, "Exact");
        UUID whole = createBooking(f, "Whole", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addContainerLine(f.owner(), whole, bookingVersion(f.owner(), whole), box);
        reserve(f.owner(), whole, bookingVersion(f.owner(), whole));
        UUID individual = createBooking(f, "Individual", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addAssetLine(f.owner(), individual, bookingVersion(f.owner(), individual), exact);
        reserve(f.owner(), individual, bookingVersion(f.owner(), individual));
        addExactRequirement(f, box, exact);
        JsonNode detail = json(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + whole, null)
                .getBody());
        assertThat(detail.path("reservationStatus").asText()).isEqualTo("ATTENTION_REQUIRED");
        UUID requirement = jdbc.sql(
                        "SELECT id FROM packing_requirement WHERE container_asset_id=:id AND specific_asset_id=:asset")
                .param("id", box)
                .param("asset", exact)
                .query(UUID.class)
                .single();
        ResponseEntity<String> warning = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/packing-requirements/" + requirement + "/archive",
                Map.of("expectedVersion", 0));
        assertThat(warning.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(warning.getBody()).path("affectedBookingIds"))
                .anySatisfy(id -> assertThat(id.asText()).isEqualTo(whole.toString()));
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/packing-requirements/" + requirement + "/archive",
                                Map.of("expectedVersion", 0, "confirmAffectedBookings", true))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(json(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + whole, null)
                                .getBody())
                        .path("reservationStatus")
                        .asText())
                .isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM event_booking_history WHERE event_booking_id=:id")
                        .param("id", whole)
                        .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void removedLinesAreArchivedAndTimestampMetadataUpdatesReturnCurrentVersions() {
        Fixture f = fixture();
        UUID b = createBooking(f, "Metadata", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        Map<String, Object> update = Map.of(
                "expectedVersion",
                bookingVersion(f.owner(), b),
                "booking",
                Map.of(
                        "name",
                        "Updated",
                        "clientText",
                        "Client",
                        "venueText",
                        "Venue",
                        "notes",
                        "Notes",
                        "startsAt",
                        "2027-01-01T09:00:00Z",
                        "endsAt",
                        "2027-01-01T10:00:00Z"));
        ResponseEntity<String> changed = exchange(f.owner(), HttpMethod.PUT, "/api/v1/bookings/" + b, update);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(changed.getBody()).path("version").asLong()).isEqualTo(bookingVersion(f.owner(), b));
        assertThat(json(changed.getBody()).path("clientText").asText()).isEqualTo("Client");
        ResponseEntity<String> added =
                addAssetLine(f.owner(), b, bookingVersion(f.owner(), b), createAsset(f, "Selected"));
        UUID line = UUID.fromString(
                json(added.getBody()).path("lines").get(0).path("id").asText());
        removeLine(f.owner(), b, line, bookingVersion(f.owner(), b));
        assertThat(jdbc.sql("SELECT archived_at IS NOT NULL FROM event_booking_line WHERE id=:id")
                        .param("id", line)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(createBookingResponse(f.owner(), "Invalid", LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings?limit=101", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lifecycleArchiveRestoreAndPlacementChangesRecalculateFutureReservations() {
        Fixture f = fixture();
        UUID asset = createAsset(f, "Lifecycle");
        UUID b = createBooking(f, "Future hold", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addAssetLine(f.owner(), b, bookingVersion(f.owner(), b), asset);
        reserve(f.owner(), b, bookingVersion(f.owner(), b));
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.PUT,
                                "/api/v1/assets/" + asset + "/lifecycle",
                                Map.of("lifecycleState", "LOST", "reason", "Missing"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertReservationStatus(f, b, "ATTENTION_REQUIRED");
        exchange(
                f.owner(),
                HttpMethod.PUT,
                "/api/v1/assets/" + asset + "/lifecycle",
                Map.of("lifecycleState", "ACTIVE", "reason", "Found"));
        assertReservationStatus(f, b, "CONFIRMED");
        assertThat(exchange(f.owner(), HttpMethod.POST, "/api/v1/assets/" + asset + "/archive", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertReservationStatus(f, b, "ATTENTION_REQUIRED");
        assertThat(exchange(f.owner(), HttpMethod.POST, "/api/v1/assets/" + asset + "/restore", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertReservationStatus(f, b, "CONFIRMED");
        UUID box = createAsset(f, createSerializedModel(f, "Case", true), "Case");
        UUID whole = createBooking(f, "Future container", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addContainerLine(f.owner(), whole, bookingVersion(f.owner(), whole), box);
        reserve(f.owner(), whole, bookingVersion(f.owner(), whole));
        place(f, asset, box);
        assertReservationStatus(f, whole, "ATTENTION_REQUIRED");
    }

    @Test
    void stockConsumptionFlagsReservationsThatNoLongerHaveAvailableQuantity() {
        Fixture f = fixture();
        UUID box = createAsset(f, createSerializedModel(f, "Stock case", true), "Stock case");
        UUID model = createQuantityModel(f, "Tape");
        UUID stock = receive(f, model, box, "10");
        UUID b = createBooking(f, "Planned issue", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addConsumableLine(f.owner(), b, bookingVersion(f.owner(), b), stock, "8");
        reserve(f.owner(), b, bookingVersion(f.owner(), b));
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/asset-models/" + model + "/consumable-stock/consume",
                                Map.of("containerAssetId", box.toString(), "quantity", 5, "note", "Used in workshop"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertReservationStatus(f, b, "ATTENTION_REQUIRED");
        receive(f, model, box, "3");
        assertReservationStatus(f, b, "CONFIRMED");
    }

    @Test
    void creationRetriesReuseAnImmutableCommandAndHistoryReadsAreBoundedAndTenantScoped() {
        Fixture f = fixture();
        Map<String, Object> body =
                new java.util.HashMap<>(bookingBody("Replay", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2)));
        body.put("mutationId", UUID.randomUUID().toString());
        ResponseEntity<String> first = exchange(f.owner(), HttpMethod.POST, "/api/v1/bookings", body),
                replay = exchange(f.owner(), HttpMethod.POST, "/api/v1/bookings", body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = json(first.getBody()).path("id").asText();
        assertThat(json(replay.getBody()).path("id").asText()).isEqualTo(id);
        assertThat(jdbc.sql("SELECT count(*) FROM event_booking_history WHERE event_booking_id=:id")
                        .param("id", UUID.fromString(id))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        Map<String, Object> edited = new java.util.HashMap<>(body);
        edited.put("name", "Edited");
        exchange(f.owner(), HttpMethod.PUT, "/api/v1/bookings/" + id, Map.of("expectedVersion", 0, "booking", edited));
        assertThat(exchange(f.owner(), HttpMethod.POST, "/api/v1/bookings", body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(exchange(f.owner(), HttpMethod.POST, "/api/v1/bookings", edited)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> history =
                exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + id + "/history?limit=1", null);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(history.getBody()).size()).isEqualTo(1);
        String cursor = json(history.getBody()).get(0).path("id").asText();
        assertThat(json(exchange(
                                        f.owner(),
                                        HttpMethod.GET,
                                        "/api/v1/bookings/" + id + "/history?limit=1&cursor=" + cursor,
                                        null)
                                .getBody())
                        .size())
                .isEqualTo(1);

        assertThat(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + id + "/history?limit=101", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        Fixture another = fixture();
        assertThat(exchange(another.owner(), HttpMethod.GET, "/api/v1/bookings/" + id + "/history", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reducingAReservedContainerRequirementNeedsExplicitConfirmation() {
        Fixture f = fixture();
        UUID model = createSerializedModel(f, "Cable", false);
        createAsset(f, model, "One");
        createAsset(f, model, "Two");
        UUID box = createAsset(f, createSerializedModel(f, "Case", true), "Case");
        requirement(f, box, model, "MODEL_QUANTITY", "2");
        UUID b = createBooking(f, "Future", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        addContainerLine(f.owner(), b, bookingVersion(f.owner(), b), box);
        reserve(f.owner(), b, bookingVersion(f.owner(), b));
        UUID req = jdbc.sql("SELECT id FROM packing_requirement WHERE container_asset_id=:id")
                .param("id", box)
                .query(UUID.class)
                .single();
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "expectedVersion",
                0,
                "requirement",
                Map.of("type", "MODEL_QUANTITY", "assetModelId", model.toString(), "requiredQuantity", 1)));
        assertThat(exchange(f.owner(), HttpMethod.PUT, "/api/v1/packing-requirements/" + req, body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        body.put("confirmAffectedBookings", true);
        assertThat(exchange(f.owner(), HttpMethod.PUT, "/api/v1/packing-requirements/" + req, body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertReservationStatus(f, b, "CONFIRMED");
    }

    @Test
    void concurrentConsumableReservationsCannotOverspendStockAcrossDisjointDates() throws Exception {
        Fixture f = fixture();
        UUID box = createAsset(f, createSerializedModel(f, "Source", true), "Source");
        UUID stock = receive(f, createQuantityModel(f, "Tape"), box, "10");
        UUID first = createBooking(f, "First", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2)),
                second = createBooking(f, "Later", LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 2));
        addConsumableLine(f.owner(), first, bookingVersion(f.owner(), first), stock, "6");
        addConsumableLine(f.owner(), second, bookingVersion(f.owner(), second), stock, "6");
        long firstVersion = bookingVersion(f.owner(), first), secondVersion = bookingVersion(f.deputy(), second);
        ResponseEntity<String>[] results = runTogether(
                () -> reserve(f.owner(), first, firstVersion), () -> reserve(f.deputy(), second, secondVersion));
        assertThat(results)
                .allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(jdbc.sql(
                                "SELECT sum(c.quantity) FROM event_booking_reservation_claim c JOIN event_booking b ON b.current_revision_id=c.reservation_revision_id WHERE c.consumable_stock_id=:stock AND c.claim_type='CONSUMABLE' AND b.status='RESERVED'")
                        .param("stock", stock)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("6");
    }

    @Test
    void concurrentModelCapacityReservationsHoldOnlyAvailablePool() throws Exception {
        Fixture f = fixture();
        UUID model = createSerializedModel(f, "Cable", false);
        createAsset(f, model, "Only cable");
        UUID cases = createSerializedModel(f, "Case", true);
        UUID first = capacityBooking(f, cases, model, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2)),
                second = capacityBooking(f, cases, model, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        long firstVersion = bookingVersion(f.owner(), first), secondVersion = bookingVersion(f.deputy(), second);
        ResponseEntity<String>[] results = runTogether(
                () -> reserve(f.owner(), first, firstVersion), () -> reserve(f.deputy(), second, secondVersion));
        assertThat(results)
                .allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(jdbc.sql("SELECT count(*) FROM event_booking WHERE id IN (:first,:second) AND status='RESERVED'")
                        .param("first", first)
                        .param("second", second)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    private void assertReservationStatus(Fixture f, UUID b, String status) {
        assertThat(json(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + b, null)
                                .getBody())
                        .path("reservationStatus")
                        .asText())
                .isEqualTo(status);
    }

    private UUID capacityBooking(Fixture f, UUID caseModel, UUID equipmentModel, LocalDate start, LocalDate end) {
        UUID box = createAsset(f, caseModel, "Case");
        requirement(f, box, equipmentModel, "MODEL_QUANTITY", "1");
        UUID b = createBooking(f, "Capacity", start, end);
        addContainerLine(f.owner(), b, bookingVersion(f.owner(), b), box);
        return b;
    }

    private void requirement(Fixture f, UUID box, UUID model, String type, String quantity) {
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/assets/" + box + "/packing-requirements",
                                Map.of(
                                        "type",
                                        type,
                                        "assetModelId",
                                        model.toString(),
                                        "requiredQuantity",
                                        new BigDecimal(quantity)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private void assertConflict(ResponseEntity<String> response, String type) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response.getBody());
        assertThat(body.path("reservable").asBoolean()).isFalse();
        assertThat(body.path("conflicts"))
                .anySatisfy(c -> assertThat(c.path("type").asText()).isEqualTo(type));
    }

    private void assertAssetOverlap(ResponseEntity<String> response, UUID asset) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode preview = json(response.getBody());
        assertThat(preview.path("reservable").asBoolean()).isFalse();
        assertThat(preview.path("conflicts")).anySatisfy(conflict -> {
            assertThat(conflict.path("type").asText()).isEqualTo("ASSET_OVERLAP");
            assertThat(conflict.path("assetId").asText()).isEqualTo(asset.toString());
        });
    }

    private UUID bookingWithAsset(Fixture fixture, String name, UUID asset) {
        UUID booking = createBooking(fixture, name, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3));
        addAssetLine(fixture.owner(), booking, bookingVersion(fixture.owner(), booking), asset);
        return booking;
    }

    private UUID createBooking(Fixture fixture, String name, LocalDate startsOn, LocalDate endsOn) {
        ResponseEntity<String> response = createBookingResponse(fixture.owner(), name, startsOn, endsOn);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(json(response.getBody()).path("id").asText());
    }

    private ResponseEntity<String> createBookingResponse(
            AuthenticatedSession session, String name, LocalDate startsOn, LocalDate endsOn) {
        return exchange(session, HttpMethod.POST, "/api/v1/bookings", bookingBody(name, startsOn, endsOn));
    }

    private static Map<String, Object> bookingBody(String name, LocalDate startsOn, LocalDate endsOn) {
        return Map.of(
                "name",
                name,
                "notes",
                "Test booking",
                "startsAt",
                startsOn.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                "endsAt",
                endsOn.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
    }

    private long bookingVersion(AuthenticatedSession session, UUID booking) {
        ResponseEntity<String> response = exchange(session, HttpMethod.GET, "/api/v1/bookings/" + booking, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(response.getBody()).path("version").asLong();
    }

    private ResponseEntity<String> addContainerLine(
            AuthenticatedSession session, UUID booking, long version, UUID asset) {
        return addLine(session, booking, version, "CONTAINER", asset, null, BigDecimal.ONE);
    }

    private ResponseEntity<String> addAssetLine(AuthenticatedSession session, UUID booking, long version, UUID asset) {
        return addLine(session, booking, version, "ASSET", asset, null, BigDecimal.ONE);
    }

    private ResponseEntity<String> addConsumableLine(
            AuthenticatedSession session, UUID booking, long version, UUID stock, String quantity) {
        return addLine(session, booking, version, "CONSUMABLE", null, stock, new BigDecimal(quantity));
    }

    private ResponseEntity<String> addLine(
            AuthenticatedSession session,
            UUID booking,
            long version,
            String type,
            UUID asset,
            UUID stock,
            BigDecimal quantity) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("expectedBookingVersion", version);
        body.put("type", type);
        body.put("assetId", asset == null ? null : asset.toString());
        body.put("consumableStockId", stock == null ? null : stock.toString());
        body.put("quantity", quantity);
        return exchange(session, HttpMethod.POST, "/api/v1/bookings/" + booking + "/lines", body);
    }

    private ResponseEntity<String> preview(AuthenticatedSession session, UUID booking) {
        return exchange(session, HttpMethod.POST, "/api/v1/bookings/" + booking + "/reservation-preview", null);
    }

    private ResponseEntity<String> reserve(AuthenticatedSession session, UUID booking, long version) {
        return exchange(
                session,
                HttpMethod.POST,
                "/api/v1/bookings/" + booking + "/reserve",
                Map.of("expectedVersion", version));
    }

    private ResponseEntity<String> cancel(AuthenticatedSession session, UUID booking, long version) {
        return exchange(
                session,
                HttpMethod.POST,
                "/api/v1/bookings/" + booking + "/cancel",
                Map.of("expectedVersion", version));
    }

    private ResponseEntity<String> removeLine(AuthenticatedSession session, UUID booking, UUID line, long version) {
        return exchange(
                session,
                HttpMethod.DELETE,
                "/api/v1/bookings/" + booking + "/lines/" + line,
                Map.of("expectedVersion", version));
    }

    private UUID createQuantityModel(Fixture fixture, String name) {
        ResponseEntity<String> response = exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        name + "-" + UUID.randomUUID(),
                        "categoryId",
                        createCategory(fixture).toString(),
                        "trackingMode",
                        "QUANTITY_STOCK",
                        "stockUnitLabel",
                        "roll",
                        "canContainAssets",
                        false));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(json(response.getBody()).path("id").asText());
    }

    private UUID createSerializedModel(Fixture fixture, String name, boolean canContainAssets) {
        ResponseEntity<String> response = exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        name + "-" + UUID.randomUUID(),
                        "categoryId",
                        createCategory(fixture).toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        canContainAssets));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(json(response.getBody()).path("id").asText());
    }

    private UUID createAsset(Fixture fixture, String name) {
        return createAsset(fixture, createSerializedModel(fixture, name + " model", false), name);
    }

    private UUID createAsset(Fixture fixture, UUID model, String name) {
        ResponseEntity<String> response = exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models/" + model + "/assets",
                Map.of("individualName", name + "-" + UUID.randomUUID(), "values", List.of()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(json(response.getBody()).path("id").asText());
    }

    private UUID createCategory(Fixture fixture) {
        ResponseEntity<String> response = exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "Bookings-" + UUID.randomUUID(), "color", "#112233"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(json(response.getBody()).path("id").asText());
    }

    private void place(Fixture fixture, UUID asset, UUID parent) {
        ResponseEntity<String> placement =
                exchange(fixture.owner(), HttpMethod.GET, "/api/v1/assets/" + asset + "/placement", null);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.PUT,
                                "/api/v1/assets/" + asset + "/placement",
                                Map.of(
                                        "parentContainerAssetId", parent.toString(),
                                        "expectedVersion",
                                                json(placement.getBody())
                                                        .path("version")
                                                        .asLong()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private void addExactRequirement(Fixture fixture, UUID container, UUID exactAsset) {
        ResponseEntity<String> response = exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/assets/" + container + "/packing-requirements",
                Map.of(
                        "type",
                        "SPECIFIC_ASSET",
                        "specificAssetReference",
                        exactAsset.toString(),
                        "requiredQuantity",
                        1));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private UUID receive(Fixture fixture, UUID model, UUID container, String quantity) {
        ResponseEntity<String> response = exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models/" + model + "/consumable-stock/receive",
                Map.of(
                        "containerAssetId",
                        container.toString(),
                        "quantity",
                        new BigDecimal(quantity),
                        "note",
                        "Initial stock"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(json(response.getBody()).path("id").asText());
    }

    private ResponseEntity<String> exchange(AuthenticatedSession session, HttpMethod method, String path, Object body) {
        HttpHeaders headers = session.headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new AssertionError("Could not parse JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<String>[] runTogether(
            Callable<ResponseEntity<String>> first, Callable<ResponseEntity<String>> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ResponseEntity<String>> firstResponse = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting to start");
                return first.call();
            });
            Future<ResponseEntity<String>> secondResponse = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting to start");
                return second.call();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return new ResponseEntity[] {
                firstResponse.get(10, TimeUnit.SECONDS), secondResponse.get(10, TimeUnit.SECONDS)
            };
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Fixture fixture() {
        Organization organization =
                organizationService.ensureOrganizationExists("Booking test org " + UUID.randomUUID());
        Map<OrganizationRole, AuthenticatedSession> sessions = new EnumMap<>(OrganizationRole.class);
        for (OrganizationRole role : OrganizationRole.values()) {
            User user = new User(
                    UUID.randomUUID(),
                    role.name().toLowerCase(Locale.ROOT) + "-booking-" + UUID.randomUUID(),
                    null,
                    "Booking test user",
                    passwordEncoder.encode(PASSWORD),
                    true,
                    clock.instant());
            users.save(user);
            memberships.save(new OrganizationMembership(
                    UUID.randomUUID(), organization.getId(), user.getId(), role, clock.instant()));
            sessions.put(role, PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD));
        }
        return new Fixture(sessions);
    }

    private record Fixture(Map<OrganizationRole, AuthenticatedSession> sessions) {
        AuthenticatedSession owner() {
            return session(OrganizationRole.OWNER);
        }

        AuthenticatedSession deputy() {
            return session(OrganizationRole.DEPUTY);
        }

        AuthenticatedSession session(OrganizationRole role) {
            return sessions.get(role);
        }
    }
}

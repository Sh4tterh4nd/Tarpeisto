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

    @Test
    void phase8ParentReturnUsesFrozenSubtreeAndCreatesOnlyContainerDependencies() {
        Fixture f = fixture();
        UUID cases = createSerializedModel(f, "Case", true);
        UUID parent = createAsset(f, cases, "Parent"),
                child = createAsset(f, cases, "Child"),
                cable = createAsset(f, "Cable");
        place(f, child, parent);
        place(f, cable, child);
        UUID b = createBooking(f, "Nested return", LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 2));
        addContainerLine(f.owner(), b, bookingVersion(f.owner(), b), parent);
        reserve(f.owner(), b, bookingVersion(f.owner(), b));
        assertThat(checkout(f, b, List.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> command = Map.of("mutationId", UUID.randomUUID());
        ResponseEntity<String> returned =
                exchange(f.owner(), HttpMethod.POST, "/api/v1/bookings/" + b + "/check-in/assets/" + parent, command);
        assertThat(returned.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(returned.getBody());
        assertThat(body.path("bookingStatus").asText()).isEqualTo("RETURNED_AUDITS_PENDING");
        assertThat(body.path("assets"))
                .hasSize(3)
                .allSatisfy(a -> assertThat(a.path("returnedAt").isNull()).isFalse());
        assertThat(body.path("auditTasks")).hasSize(2).anySatisfy(t -> {
            assertThat(t.path("containerAssetId").asText()).isEqualTo(child.toString());
            assertThat(t.path("state").asText()).isEqualTo("READY");
            assertThat(t.path("dependsOnTaskIds")).isEmpty();
        });
        assertThat(exchange(f.owner(), HttpMethod.POST, "/api/v1/bookings/" + b + "/check-in/assets/" + parent, command)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("UPDATE checkout_manifest_asset SET audit_released_at=now() WHERE physical_asset_id=:asset")
                        .param("asset", cable)
                        .update())
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE checkout_manifest_asset SET returned_at=NULL WHERE physical_asset_id=:asset")
                        .param("asset", cable)
                        .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void phase8StandaloneConcurrentReturnsCompleteAndReleaseCustody() throws Exception {
        Fixture f = fixture();
        UUID first = createAsset(f, "First"), second = createAsset(f, "Second");
        UUID b = bookingWithAsset(f, "Individual return", first);
        addAssetLine(f.owner(), b, bookingVersion(f.owner(), b), second);
        reserve(f.owner(), b, bookingVersion(f.owner(), b));
        assertThat(checkout(f, b, List.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String>[] responses = runTogether(
                () -> exchange(
                        f.owner(),
                        HttpMethod.POST,
                        "/api/v1/bookings/" + b + "/check-in/assets/" + first,
                        Map.of("mutationId", UUID.randomUUID())),
                () -> exchange(
                        f.deputy(),
                        HttpMethod.POST,
                        "/api/v1/bookings/" + b + "/check-in/assets/" + second,
                        Map.of("mutationId", UUID.randomUUID())));
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(json(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + b + "/checkout-manifest", null)
                                .getBody())
                        .path("bookingStatus")
                        .asText())
                .isEqualTo("COMPLETED");
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM checkout_manifest_asset WHERE physical_asset_id IN (:first,:second) AND audit_released_at IS NULL")
                        .param("first", first)
                        .param("second", second)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void phase8ConsumableReturnsAreIdempotentBoundedAndExplicitlyAccounted() throws Exception {
        Fixture f = fixture();
        UUID cases = createSerializedModel(f, "Case", true),
                source = createAsset(f, cases, "Source"),
                destination = createAsset(f, cases, "Return bin");
        UUID stock = receive(f, createQuantityModel(f, "Tape"), source, "5");
        UUID b = createBooking(f, "Tape issue", LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 2));
        addConsumableLine(f.owner(), b, bookingVersion(f.owner(), b), stock, "3");
        reserve(f.owner(), b, bookingVersion(f.owner(), b));
        ResponseEntity<String> checked = checkout(f, b, List.of());
        assertThat(checked.getStatusCode()).isEqualTo(HttpStatus.OK);
        String line =
                json(checked.getBody()).path("consumables").get(0).path("id").asText();
        Map<String, Object> returnCommand =
                Map.of("mutationId", UUID.randomUUID(), "quantity", 2, "destinationContainerAssetId", destination);
        String path = "/api/v1/bookings/" + b + "/check-in/consumables/" + line;
        ResponseEntity<String>[] retries = runTogether(
                () -> exchange(f.owner(), HttpMethod.POST, path, returnCommand),
                () -> exchange(f.deputy(), HttpMethod.POST, path, returnCommand));
        assertThat(retries).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(json(retries[0].getBody())
                        .path("consumables")
                        .get(0)
                        .path("returnedQuantity")
                        .decimalValue())
                .isEqualByComparingTo("2");
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                path,
                                Map.of(
                                        "mutationId",
                                        UUID.randomUUID(),
                                        "quantity",
                                        2,
                                        "destinationContainerAssetId",
                                        destination))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> complete = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/bookings/" + b + "/check-in/complete",
                Map.of("mutationId", UUID.randomUUID()));
        assertThat(complete.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(complete.getBody()).path("bookingStatus").asText()).isEqualTo("COMPLETED");
        assertThat(json(complete.getBody())
                        .path("consumables")
                        .get(0)
                        .path("consumedQuantity")
                        .decimalValue())
                .isEqualByComparingTo("1");
        assertThat(jdbc.sql("SELECT sum(quantity_delta) FROM stock_movement WHERE event_reference_id=:booking")
                        .param("booking", b)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("-1");
    }

    @Test
    void phase8SelectedCapacityAssetsBecomeFrozenPhysicalContents() {
        Fixture f = fixture();
        UUID model = createSerializedModel(f, "Cable", false),
                cable = createAsset(f, model, "Replacement"),
                cases = createSerializedModel(f, "Case", true);
        UUID b = capacityBooking(f, cases, model, LocalDate.of(2027, 4, 1), LocalDate.of(2027, 4, 2));
        reserve(f.owner(), b, bookingVersion(f.owner(), b));
        ResponseEntity<String> checked = checkout(f, b, List.of(cable));
        assertThat(checked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(checked.getBody()).path("assets")).anySatisfy(a -> {
            assertThat(a.path("assetId").asText()).isEqualTo(cable.toString());
            assertThat(a.path("actualParentContainerAssetId").isNull()).isFalse();
        });
    }

    @Test
    void phase8CheckoutAndReturnEnforceRolesTenancyAndCommandFingerprints() {
        Fixture f = fixture(), other = fixture();
        UUID asset = createAsset(f, "Private asset"), b = bookingWithAsset(f, "Private event", asset);
        reserve(f.owner(), b, bookingVersion(f.owner(), b));
        UUID mutation = UUID.randomUUID();
        long version = bookingVersion(f.owner(), b);
        Map<String, Object> command = Map.of("mutationId", mutation, "expectedVersion", version);
        String checkoutPath = "/api/v1/bookings/" + b + "/checkout";
        assertThat(exchange(f.session(OrganizationRole.VIEWER), HttpMethod.POST, checkoutPath, command)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(other.owner(), HttpMethod.POST, checkoutPath, command)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
                                f.session(OrganizationRole.OPERATOR_AUDITOR),
                                HttpMethod.POST,
                                checkoutPath,
                                Map.of(
                                        "mutationId",
                                        mutation,
                                        "expectedVersion",
                                        version,
                                        "overrideReason",
                                        "Unauthorized override"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(f.session(OrganizationRole.OPERATOR_AUDITOR), HttpMethod.POST, checkoutPath, command)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(f.owner(), HttpMethod.POST, checkoutPath, command).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                checkoutPath,
                                Map.of(
                                        "mutationId",
                                        mutation,
                                        "expectedVersion",
                                        version,
                                        "overrideReason",
                                        "Changed command"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        String returnPath = "/api/v1/bookings/" + b + "/check-in/assets/" + asset;
        assertThat(exchange(f.owner(), HttpMethod.POST, returnPath, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(other.owner(), HttpMethod.POST, returnPath, Map.of("mutationId", UUID.randomUUID()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void phase8CheckedOutContainerStockCannotBeSeparatelyReserved() {
        Fixture f = fixture();
        UUID cases = createSerializedModel(f, "Case", true),
                box = createAsset(f, cases, "Away case"),
                stock = receive(f, createQuantityModel(f, "Tape"), box, "5");
        UUID b = createBooking(f, "Carried tape", LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 2));
        addContainerLine(f.owner(), b, bookingVersion(f.owner(), b), box);
        reserve(f.owner(), b, bookingVersion(f.owner(), b));
        assertThat(checkout(f, b, List.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID issue = createBooking(f, "Issue away tape", LocalDate.of(2027, 6, 1), LocalDate.of(2027, 6, 2));
        addConsumableLine(f.owner(), issue, bookingVersion(f.owner(), issue), stock, "1");
        assertConflict(preview(f.owner(), issue), "SOURCE_CONTAINER_UNAVAILABLE");
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/assets/" + box + "/packing-requirements",
                                Map.of(
                                        "type",
                                        "CONSUMABLE_QUANTITY",
                                        "assetModelId",
                                        createQuantityModel(f, "Other tape"),
                                        "requiredQuantity",
                                        1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void phase8FlexibleCheckoutMustReplaceAnIndividuallyHeldIdentity() {
        Fixture f = fixture();
        UUID model = createSerializedModel(f, "Cable", false),
                original = createAsset(f, model, "Original"),
                replacement = createAsset(f, model, "Replacement"),
                box = createAsset(f, createSerializedModel(f, "Case", true), "Case");
        place(f, original, box);
        requirement(f, box, model, "MODEL_QUANTITY", "1");
        UUID individual = createBooking(f, "Individual hold", LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 2));
        addAssetLine(f.owner(), individual, bookingVersion(f.owner(), individual), original);
        reserve(f.owner(), individual, bookingVersion(f.owner(), individual));
        UUID b = createBooking(f, "Whole case", LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 2));
        addContainerLine(f.owner(), b, bookingVersion(f.owner(), b), box);
        reserve(f.owner(), b, bookingVersion(f.owner(), b));
        assertThat(checkout(f, b, List.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> checked = checkout(f, b, List.of(replacement));
        assertThat(checked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(checked.getBody()).path("assets"))
                .noneSatisfy(a -> assertThat(a.path("assetId").asText()).isEqualTo(original.toString()));
        assertThat(jdbc.sql("SELECT parent_container_asset_id IS NULL FROM physical_asset WHERE id=:asset")
                        .param("asset", original)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void phase91ScannerRetriesRejectChangedPayloadUndoPersistsAndCompletedObservationsAreFrozen() {
        Fixture f = fixture();
        UUID box = createAsset(f, createSerializedModel(f, "Audit box", true), "Box");
        UUID cable = createAsset(f, "Audit cable");
        addExactRequirement(f, box, cable);
        place(f, cable, box);
        UUID booking = returnedContainers(f, List.of(box));
        UUID audit = startAudit(f, box);
        JsonNode expectedBefore = json(exchange(
                                f.owner(), HttpMethod.GET, "/api/v1/audits/tasks/" + auditTask(box), null)
                        .getBody())
                .path("expectedRequirements")
                .get(0);
        String frozenSnapshot = expectedBefore.path("snapshot").asText();
        assertThat(json(frozenSnapshot).path("assetCode").asText()).isEqualTo(publicCode(cable));
        assertThat(json(frozenSnapshot).path("assetName").asText()).isNotBlank();
        assertThat(json(frozenSnapshot).path("modelName").asText()).isNotBlank();
        jdbc.sql("UPDATE physical_asset SET individual_name='Renamed after audit start' WHERE id=:id")
                .param("id", cable)
                .update();
        assertThat(json(exchange(f.owner(), HttpMethod.GET, "/api/v1/audits/tasks/" + auditTask(box), null)
                                .getBody())
                        .path("expectedRequirements")
                        .get(0)
                        .path("snapshot")
                        .asText())
                .isEqualTo(frozenSnapshot);
        UUID operation = UUID.randomUUID();
        Map<String, Object> scan = Map.of("operationId", operation, "code", publicCode(cable));
        String scansPath = "/api/v1/audits/" + audit + "/scans";
        ResponseEntity<String> response = exchange(f.owner(), HttpMethod.POST, scansPath, scan);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response.getBody()).path("scans")).hasSize(1);
        assertThat(exchange(f.owner(), HttpMethod.POST, scansPath, scan).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                scansPath,
                                Map.of("operationId", operation, "code", publicCode(box)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        String scanId = json(response.getBody()).path("scans").get(0).path("id").asText();
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                scansPath + "/" + scanId + "/undo",
                                Map.of("operationId", UUID.randomUUID()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("SELECT undone_at IS NOT NULL FROM audit_scan WHERE id=:id")
                        .param("id", UUID.fromString(scanId))
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                scansPath,
                                Map.of("operationId", UUID.randomUUID(), "code", publicCode(cable)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID completeOperation = UUID.randomUUID();
        Map<String, Object> complete =
                Map.of("operationId", completeOperation, "containerCode", publicCode(box), "confirmMissing", false);
        String completionPath = "/api/v1/audits/" + audit + "/complete";
        ResponseEntity<String> completed = exchange(f.owner(), HttpMethod.POST, completionPath, complete);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(completed.getBody()).path("completionOutcome").asText()).isEqualTo("CLEAN");
        assertThat(exchange(f.owner(), HttpMethod.POST, completionPath, complete)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                completionPath,
                                Map.of(
                                        "operationId",
                                        completeOperation,
                                        "containerCode",
                                        publicCode(box),
                                        "confirmMissing",
                                        true))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + booking, null)
                                .getBody())
                        .path("status")
                        .asText())
                .isEqualTo("COMPLETED");
        assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE audit_scan SET undone_at=now(), undone_by_user_id=scanned_by_user_id WHERE id=:id")
                        .param("id", UUID.fromString(scanId))
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO audit_operation(id,organization_id,container_audit_id,client_operation_id,action,fingerprint,recorded_at) SELECT :id,organization_id,id,:op,'SCAN','new',now() FROM container_audit WHERE id=:audit")
                        .param("id", UUID.randomUUID())
                        .param("op", UUID.randomUUID())
                        .param("audit", audit)
                        .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void phase91WarehouseReplacementCannotHideManifestLossAndFindingChildKeepsParentBlocked() {
        Fixture f = fixture();
        UUID caseModel = createSerializedModel(f, "Audit cases", true);
        UUID root = createAsset(f, caseModel, "Root"), child = createAsset(f, caseModel, "Child");
        UUID cableModel = createSerializedModel(f, "Cables", false);
        UUID issued = createAsset(f, cableModel, "Issued"), replacement = createAsset(f, cableModel, "Replacement");
        requirement(f, child, cableModel, "MODEL_QUANTITY", "1");
        place(f, issued, child);
        place(f, child, root);
        returnedContainers(f, List.of(root));
        UUID audit = startAudit(f, child);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/audits/" + audit + "/scans",
                                Map.of("operationId", UUID.randomUUID(), "code", publicCode(replacement)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> completed = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/audits/" + audit + "/complete",
                Map.of("operationId", UUID.randomUUID(), "containerCode", publicCode(child), "confirmMissing", false));
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(completed.getBody()).path("completionOutcome").asText()).isEqualTo("FINDINGS");
        UUID parentTask = auditTask(root);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/audits/tasks/" + parentTask + "/start",
                                Map.of("containerCode", publicCode(root)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void phase91CableSwapsCompleteButWarehouseCableLeavesExactManifestMissing() {
        Fixture f = fixture();
        UUID model = createSerializedModel(f, "Swap cable", false),
                caseModel = createSerializedModel(f, "Swap case", true);
        UUID first = createAsset(f, caseModel, "First"), second = createAsset(f, caseModel, "Second");
        UUID a = createAsset(f, model, "A"),
                b = createAsset(f, model, "B"),
                warehouse = createAsset(f, model, "Warehouse");
        requirement(f, first, model, "MODEL_QUANTITY", "1");
        requirement(f, second, model, "MODEL_QUANTITY", "1");
        place(f, a, first);
        place(f, b, second);
        returnedContainers(f, List.of(first, second));
        UUID firstAudit = startAudit(f, first), secondAudit = startAudit(f, second);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/audits/" + firstAudit + "/scans",
                                Map.of("operationId", UUID.randomUUID(), "code", publicCode(b)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/audits/" + secondAudit + "/scans",
                                Map.of("operationId", UUID.randomUUID(), "code", publicCode(b)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/audits/" + secondAudit + "/move-code-here",
                                Map.of("operationId", UUID.randomUUID(), "code", publicCode(b)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/audits/" + firstAudit + "/scans",
                                Map.of("operationId", UUID.randomUUID(), "code", publicCode(warehouse)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/audits/" + firstAudit + "/complete",
                                Map.of(
                                        "operationId",
                                        UUID.randomUUID(),
                                        "containerCode",
                                        publicCode(first),
                                        "confirmMissing",
                                        false))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> completed = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/audits/" + secondAudit + "/complete",
                Map.of("operationId", UUID.randomUUID(), "containerCode", publicCode(second), "confirmMissing", false));
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(completed.getBody()).path("findings")).anySatisfy(finding -> {
            assertThat(finding.path("assetId").asText()).isEqualTo(a.toString());
            assertThat(finding.path("type").asText()).isEqualTo("MISSING");
        });
    }

    @Test
    void phase91ExactPinnedElsewhereCannotFillModelSlotAndRolesAndTenantsAreEnforced() {
        Fixture f = fixture(), other = fixture();
        UUID cases = createSerializedModel(f, "Pinned cases", true),
                model = createSerializedModel(f, "Pinned model", false);
        UUID box = createAsset(f, cases, "Audited box"), destination = createAsset(f, cases, "Exact destination");
        UUID exact = createAsset(f, model, "Pinned"), eligible = createAsset(f, model, "Eligible");
        addExactRequirement(f, destination, exact);
        requirement(f, box, model, "MODEL_QUANTITY", "1");
        place(f, eligible, box);
        returnedContainers(f, List.of(box));
        UUID task = auditTask(box);
        assertThat(exchange(f.session(OrganizationRole.VIEWER), HttpMethod.GET, "/api/v1/audits/tasks/" + task, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(other.owner(), HttpMethod.GET, "/api/v1/audits/tasks/" + task, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
                                f.session(OrganizationRole.VIEWER),
                                HttpMethod.POST,
                                "/api/v1/audits/tasks/" + task + "/start",
                                Map.of("containerCode", publicCode(box)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        UUID audit = startAudit(f, box);
        ResponseEntity<String> response = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/audits/" + audit + "/scans",
                Map.of("operationId", UUID.randomUUID(), "code", publicCode(exact)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response.getBody()).path("scans").get(0).path("outcome").asText())
                .isEqualTo("MISPLACED");
        JsonNode context = json(json(response.getBody())
                .path("scans")
                .get(0)
                .path("contextSnapshot")
                .asText());
        assertThat(context.path("destinationContainerId").asText()).isEqualTo(destination.toString());
        assertThat(context.path("destinationContainerCode").asText()).isEqualTo(publicCode(destination));
        assertThat(context.path("destinationContainerName").asText()).isNotBlank();
        assertThat(json(response.getBody())
                        .path("expectedRequirements")
                        .get(0)
                        .path("satisfied")
                        .asBoolean())
                .isFalse();
    }

    @Test
    void phase91AuditorCanConfirmConsumablesButOnlyDeputyCanAdjustReturnedContainerBalance() {
        Fixture f = fixture();
        UUID box = createAsset(f, createSerializedModel(f, "Consumable audit case", true), "Box"),
                model = createQuantityModel(f, "Audit tape");
        receive(f, model, box, "3");
        requirement(f, box, model, "CONSUMABLE_QUANTITY", "2");
        returnedContainers(f, List.of(box));
        UUID audit = startAudit(f, box);
        UUID expectedId = jdbc.sql("SELECT id FROM audit_expected_requirement WHERE container_audit_id=:audit")
                .param("audit", audit)
                .query(UUID.class)
                .single();
        String path = "/api/v1/audits/" + audit + "/consumables/" + expectedId;
        assertThat(exchange(
                                f.session(OrganizationRole.OPERATOR_AUDITOR),
                                HttpMethod.POST,
                                path,
                                Map.of(
                                        "operationId",
                                        UUID.randomUUID(),
                                        "status",
                                        "OBSERVED",
                                        "observedQuantity",
                                        2,
                                        "reason",
                                        "Returned count"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        UUID operation = UUID.randomUUID();
        Map<String, Object> observed = Map.of(
                "operationId", operation, "status", "OBSERVED", "observedQuantity", 1, "reason", "Returned count");
        ResponseEntity<String> response = exchange(f.deputy(), HttpMethod.POST, path, observed);
        assertThat(response.getStatusCode()).withFailMessage(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(response.getBody())
                        .path("expectedRequirements")
                        .get(0)
                        .path("satisfied")
                        .asBoolean())
                .isFalse();
        assertThat(exchange(f.deputy(), HttpMethod.POST, path, observed).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                f.deputy(),
                                HttpMethod.POST,
                                path,
                                Map.of(
                                        "operationId",
                                        operation,
                                        "status",
                                        "OBSERVED",
                                        "observedQuantity",
                                        2,
                                        "reason",
                                        "Changed count"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.sql(
                                "SELECT quantity FROM consumable_stock_balance WHERE container_asset_id=:container AND asset_model_id=:model")
                        .param("container", box)
                        .param("model", model)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("1");
    }

    @Test
    void phase91InterchangeableCableSwapCompletesCleanlyWithoutManualPlacement() {
        Fixture f = fixture();
        UUID cases = createSerializedModel(f, "Clean swap cases", true),
                cables = createSerializedModel(f, "Clean swap cables", false);
        UUID first = createAsset(f, cases, "First"), second = createAsset(f, cases, "Second");
        UUID a = createAsset(f, cables, "A"), b = createAsset(f, cables, "B");
        requirement(f, first, cables, "MODEL_QUANTITY", "1");
        requirement(f, second, cables, "MODEL_QUANTITY", "1");
        place(f, a, first);
        place(f, b, second);
        UUID booking = returnedContainers(f, List.of(first, second));
        UUID firstAudit = startAudit(f, first), secondAudit = startAudit(f, second);
        for (Map.Entry<UUID, UUID> pair : Map.of(firstAudit, b, secondAudit, a).entrySet())
            assertThat(exchange(
                                    f.owner(),
                                    HttpMethod.POST,
                                    "/api/v1/audits/" + pair.getKey() + "/scans",
                                    Map.of("operationId", UUID.randomUUID(), "code", publicCode(pair.getValue())))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        for (Map.Entry<UUID, UUID> pair :
                Map.of(firstAudit, first, secondAudit, second).entrySet()) {
            ResponseEntity<String> response = exchange(
                    f.owner(),
                    HttpMethod.POST,
                    "/api/v1/audits/" + pair.getKey() + "/complete",
                    Map.of(
                            "operationId",
                            UUID.randomUUID(),
                            "containerCode",
                            publicCode(pair.getValue()),
                            "confirmMissing",
                            false));
            assertThat(response.getStatusCode())
                    .withFailMessage(response.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(json(response.getBody()).path("completionOutcome").asText())
                    .isEqualTo("CLEAN");
        }
        assertThat(jdbc.sql("SELECT parent_container_asset_id FROM physical_asset WHERE id=:asset")
                        .param("asset", a)
                        .query(UUID.class)
                        .single())
                .isEqualTo(second);
        assertThat(jdbc.sql("SELECT parent_container_asset_id FROM physical_asset WHERE id=:asset")
                        .param("asset", b)
                        .query(UUID.class)
                        .single())
                .isEqualTo(first);
        assertThat(json(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + booking, null)
                                .getBody())
                        .path("status")
                        .asText())
                .isEqualTo("COMPLETED");
    }

    @Test
    void phase91ExtraScanSuggestsCompatibleContainerAndConsumableSnapshotIncludesUnit() {
        Fixture f = fixture();
        UUID cases = createSerializedModel(f, "Suggestion cases", true),
                cables = createSerializedModel(f, "Suggestion cables", false);
        UUID first = createAsset(f, cases, "First"), second = createAsset(f, cases, "Second");
        UUID a = createAsset(f, cables, "A"), b = createAsset(f, cables, "B"), extra = createAsset(f, cables, "Extra");
        UUID tape = createQuantityModel(f, "Suggested tape");
        receive(f, tape, first, "2");
        requirement(f, first, tape, "CONSUMABLE_QUANTITY", "2");
        requirement(f, first, cables, "MODEL_QUANTITY", "1");
        requirement(f, second, cables, "MODEL_QUANTITY", "1");
        place(f, a, first);
        place(f, b, second);
        returnedContainers(f, List.of(first, second));
        UUID audit = startAudit(f, first);
        JsonNode auditView = json(exchange(f.owner(), HttpMethod.GET, "/api/v1/audits/tasks/" + auditTask(first), null)
                .getBody());
        assertThat(auditView.path("expectedRequirements")).anySatisfy(row -> {
            assertThat(row.path("type").asText()).isEqualTo("CONSUMABLE_QUANTITY");
            JsonNode snapshot = json(row.path("snapshot").asText());
            assertThat(snapshot.path("modelName").asText()).isNotBlank();
            assertThat(snapshot.path("stockUnitLabel").asText()).isNotBlank();
        });
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/audits/" + audit + "/scans",
                                Map.of("operationId", UUID.randomUUID(), "code", publicCode(a)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> response = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/audits/" + audit + "/scans",
                Map.of("operationId", UUID.randomUUID(), "code", publicCode(extra)));
        assertThat(response.getStatusCode()).withFailMessage(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode extraScan = json(response.getBody()).path("scans").get(1);
        assertThat(extraScan.path("outcome").asText()).isEqualTo("EXTRA");
        assertThat(json(extraScan.path("contextSnapshot").asText()).path("suggestions"))
                .anySatisfy(suggestion -> {
                    assertThat(suggestion.path("containerId").asText()).isEqualTo(second.toString());
                    assertThat(suggestion.path("code").asText()).isEqualTo(publicCode(second));
                    assertThat(suggestion.path("name").asText()).isNotBlank();
                });
    }

    @Test
    void phase10ResolutionReplaysConflictAndRepairClosurePreserveImmutableAuditAndStateHistory() {
        Fixture f = fixture();
        UUID box = createAsset(f, createSerializedModel(f, "Review case", true), "Case");
        UUID cable = createAsset(f, "Damaged cable");
        addExactRequirement(f, box, cable);
        place(f, cable, box);
        UUID booking = returnedContainers(f, List.of(box));
        UUID audit = startAudit(f, box);
        scanForReview(f, audit, cable);
        ResponseEntity<String> observation = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/audits/" + audit + "/findings",
                Map.of(
                        "operationId",
                        UUID.randomUUID(),
                        "type",
                        "DAMAGED",
                        "assetId",
                        cable,
                        "note",
                        "Cracked connector"));
        assertThat(observation.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID finding = UUID.fromString(
                json(observation.getBody()).path("findings").get(0).path("id").asText());
        assertThat(completeForReview(f, audit, box, false, false).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String frozen = exchange(f.owner(), HttpMethod.GET, "/api/v1/audits/tasks/" + auditTask(box), null)
                .getBody();
        Map<String, Object> command =
                Map.of("operationId", UUID.randomUUID(), "action", "CREATE_REPAIR", "repairReference", "R-101");
        String path = "/api/v1/findings/" + finding + "/resolutions";
        assertThat(exchange(f.deputy(), HttpMethod.POST, path, command).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(f.deputy(), HttpMethod.POST, path, command).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                path,
                                Map.of(
                                        "operationId",
                                        command.get("operationId"),
                                        "action",
                                        "DISMISS",
                                        "note",
                                        "different"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                path,
                                Map.of("operationId", UUID.randomUUID(), "action", "DISMISS", "note", "different"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange(f.owner(), HttpMethod.GET, "/api/v1/audits/tasks/" + auditTask(box), null)
                        .getBody())
                .isEqualTo(frozen);
        assertThat(json(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + booking, null)
                                .getBody())
                        .path("status")
                        .asText())
                .isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_finding_resolution WHERE audit_finding_id=:id")
                        .param("id", finding)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        UUID repair = jdbc.sql("SELECT id FROM asset_repair WHERE asset_id=:id")
                .param("id", cable)
                .query(UUID.class)
                .single();
        UUID future = createBooking(f, "Future equipment", LocalDate.of(2027, 6, 1), LocalDate.of(2027, 6, 2));
        assertThat(addAssetLine(f.owner(), future, bookingVersion(f.owner(), future), cable)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertConflict(preview(f.owner(), future), "ASSET_UNAVAILABLE");
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/repairs/" + repair + "/close",
                                Map.of("resultingCondition", "DAMAGED"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM asset_state_history WHERE asset_id=:id AND change_type='CONDITION' AND new_value='DAMAGED'")
                        .param("id", cable)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/repairs/" + repair + "/close",
                                Map.of("resultingCondition", "GOOD"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(preview(f.owner(), future).getBody()).path("reservable").asBoolean())
                .isTrue();
        assertThatThrownBy(() -> jdbc.sql("UPDATE asset_repair SET reference_or_description='rewrite' WHERE id=:id")
                        .param("id", repair)
                        .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void phase10FormalLostAccountingDoesNotInventPhysicalReturnAndReplacementGetsANewIdentity() {
        Fixture f = fixture();
        UUID box = createAsset(f, createSerializedModel(f, "Empty return case", true), "Case");
        UUID missing = createAsset(f, "Lost separately issued asset");
        UUID booking = createBooking(f, "Partial return", LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 2));
        addContainerLine(f.owner(), booking, bookingVersion(f.owner(), booking), box);
        addAssetLine(f.owner(), booking, bookingVersion(f.owner(), booking), missing);
        assertThat(reserve(f.owner(), booking, bookingVersion(f.owner(), booking))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(checkout(f, booking, List.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/bookings/" + booking + "/check-in/assets/" + box,
                                Map.of("mutationId", UUID.randomUUID()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID audit = startAudit(f, box);
        ResponseEntity<String> result = completeForReview(f, audit, box, false, false);
        assertThat(result.getStatusCode()).withFailMessage(result.getBody()).isEqualTo(HttpStatus.OK);
        UUID finding = UUID.fromString(
                json(result.getBody()).path("findings").get(0).path("id").asText());
        assertThat(resolveForReview(f, finding, "MARK_LOST", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql(
                                "SELECT returned_at IS NULL AND audit_released_at IS NOT NULL FROM checkout_manifest_asset WHERE physical_asset_id=:id")
                        .param("id", missing)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM manifest_asset_accounting accounting JOIN checkout_manifest_asset item ON item.id=accounting.checkout_manifest_asset_id WHERE item.physical_asset_id=:id AND accounting.accounting_state='LOST'")
                        .param("id", missing)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM asset_state_history WHERE asset_id=:id AND new_value='LOST'")
                        .param("id", missing)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(json(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + booking, null)
                                .getBody())
                        .path("status")
                        .asText())
                .isEqualTo("COMPLETED");
        String oldCode = publicCode(missing);
        ResponseEntity<String> replacement = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/assets/" + missing + "/replacement",
                Map.of("individualName", "Replacement"));
        assertThat(replacement.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID newId = UUID.fromString(json(replacement.getBody()).path("id").asText());
        assertThat(newId).isNotEqualTo(missing);
        assertThat(publicCode(newId)).isNotEqualTo(oldCode);
        assertThat(publicCode(missing)).isEqualTo(oldCode);
        assertThat(jdbc.sql("SELECT replaces_asset_id FROM physical_asset WHERE id=:id")
                        .param("id", newId)
                        .query(UUID.class)
                        .single())
                .isEqualTo(missing);
        assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE manifest_asset_accounting SET accounting_state='DESTROYED' WHERE checkout_manifest_asset_id IN (SELECT id FROM checkout_manifest_asset WHERE physical_asset_id=:id)")
                        .param("id", missing)
                        .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void phase10ReviewedChildUnlocksParentAndSealBreakRequiresFreshAttemptsWithoutOldScanConflicts() {
        Fixture f = fixture();
        UUID model = createSerializedModel(f, "Nested sealed case", true);
        UUID root = createAsset(f, model, "Root"), child = createAsset(f, model, "Child");
        UUID cable = createAsset(f, "Sealed cable");
        addExactRequirement(f, child, cable);
        addExactRequirement(f, root, child);
        place(f, cable, child);
        place(f, child, root);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.PUT,
                                "/api/v1/assets/" + child + "/sealable",
                                Map.of("sealable", true))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        UUID booking = returnedContainers(f, List.of(root));
        UUID audit = startAudit(f, child);
        scanForReview(f, audit, cable);
        assertThat(completeForReview(f, audit, child, false, false).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> findingResponse = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/audits/" + audit + "/findings",
                Map.of(
                        "operationId",
                        UUID.randomUUID(),
                        "type",
                        "DAMAGED",
                        "assetId",
                        cable,
                        "note",
                        "Review seal contents"));
        UUID finding = UUID.fromString(json(findingResponse.getBody())
                .path("findings")
                .get(0)
                .path("id")
                .asText());
        assertThat(completeForReview(f, audit, child, false, true).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("SELECT state FROM audit_task WHERE id=:id")
                        .param("id", auditTask(root))
                        .query(String.class)
                        .single())
                .isEqualTo("BLOCKED");
        assertThat(resolveForReview(f, finding, "DISMISS", Map.of("note", "Checked and usable"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("SELECT state FROM audit_task WHERE id=:id")
                        .param("id", auditTask(root))
                        .query(String.class)
                        .single())
                .isEqualTo("READY");
        UUID rootAudit = startAudit(f, root);
        scanForReview(f, rootAudit, child);
        assertThat(completeForReview(f, rootAudit, root, false, false).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(json(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + booking, null)
                                .getBody())
                        .path("status")
                        .asText())
                .isEqualTo("COMPLETED");
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/assets/" + child + "/seal/break",
                                Map.of("note", "Reopen contents"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT state FROM audit_task WHERE id=:id")
                        .param("id", auditTask(root))
                        .query(String.class)
                        .single())
                .isEqualTo("BLOCKED");
        assertThat(json(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + booking, null)
                                .getBody())
                        .path("status")
                        .asText())
                .isEqualTo("RETURNED_AUDITS_PENDING");
        assertThat(jdbc.sql("SELECT last_verified_at IS NULL FROM physical_asset WHERE id=:id")
                        .param("id", child)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        UUID fresh = startAudit(f, child);
        assertThat(fresh).isNotEqualTo(audit);
        scanForReview(f, fresh, cable);
        assertThat(completeForReview(f, fresh, child, false, true).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID freshParent = startAudit(f, root);
        scanForReview(f, freshParent, child);
        assertThat(completeForReview(f, freshParent, root, false, false).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("SELECT max(attempt_number) FROM container_audit WHERE audit_task_id=:id")
                        .param("id", auditTask(child))
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM asset_seal_history WHERE asset_id=:id AND action='VERIFIED'")
                        .param("id", child)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_scan WHERE physical_asset_id=:id AND undone_at IS NULL")
                        .param("id", cable)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbc.sql("UPDATE container_audit SET final_container_code=NULL WHERE id=:id")
                        .param("id", audit)
                        .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void phase10ResolutionEnforcesTenantActionAndCycleBoundaries() {
        Fixture f = fixture(), other = fixture();
        UUID model = createSerializedModel(f, "Review destinations", true);
        UUID root = createAsset(f, model, "Root"), child = createAsset(f, model, "Child");
        UUID foreign = createAsset(other, createSerializedModel(other, "Other case", true), "Other");
        place(f, child, root);
        returnedContainers(f, List.of(root));
        UUID audit = startAudit(f, child);
        ResponseEntity<String> result = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/audits/" + audit + "/findings",
                Map.of("operationId", UUID.randomUUID(), "type", "UNEXPECTED", "assetId", root));
        UUID finding = UUID.fromString(
                json(result.getBody()).path("findings").get(0).path("id").asText());
        assertThat(completeForReview(f, audit, child, false, false).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String path = "/api/v1/findings/" + finding + "/resolutions";
        assertThat(exchange(other.owner(), HttpMethod.GET, "/api/v1/findings/" + finding, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
                                f.session(OrganizationRole.OPERATOR_AUDITOR),
                                HttpMethod.POST,
                                path,
                                Map.of("operationId", UUID.randomUUID(), "action", "DISMISS", "note", "operator"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resolveForReview(f, finding, "MARK_LOST", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resolveForReview(f, finding, "REASSIGN_CURRENT_CONTAINER", Map.of("targetContainerAssetId", foreign))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resolveForReview(f, finding, "REASSIGN_CURRENT_CONTAINER", Map.of("targetContainerAssetId", child))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_finding_resolution WHERE audit_finding_id=:id")
                        .param("id", finding)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private void scanForReview(Fixture f, UUID audit, UUID asset) {
        ResponseEntity<String> result = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/audits/" + audit + "/scans",
                Map.of("operationId", UUID.randomUUID(), "code", publicCode(asset)));
        assertThat(result.getStatusCode()).withFailMessage(result.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void phase10OneOpenRepairConcurrencyAndFutureReservationsRespectAssetModelAndStockSourceAvailability()
            throws Exception {
        Fixture f = fixture();
        UUID cableModel = createSerializedModel(f, "Repair capacity", false);
        UUID cable = createAsset(f, cableModel, "Cable");
        UUID cases = createSerializedModel(f, "Repair source cases", true);
        UUID box = createAsset(f, cases, "Source case");
        UUID stockModel = createQuantityModel(f, "Repair source tape");
        UUID stockId = receive(f, stockModel, box, "10");
        UUID future = createBooking(f, "Future exact", LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 2));
        addAssetLine(f.owner(), future, bookingVersion(f.owner(), future), cable);
        assertThat(reserve(f.owner(), future, bookingVersion(f.owner(), future)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String path = "/api/v1/assets/" + cable + "/repairs";
        ResponseEntity<String>[] results = runTogether(
                () -> exchange(f.owner(), HttpMethod.POST, path, Map.of("referenceOrDescription", "Repair A")),
                () -> exchange(f.deputy(), HttpMethod.POST, path, Map.of("referenceOrDescription", "Repair B")));
        assertThat(List.of(results[0].getStatusCode(), results[1].getStatusCode()))
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertReservationStatus(f, future, "ATTENTION_REQUIRED");
        UUID capacity = capacityBooking(f, cases, cableModel, LocalDate.of(2027, 8, 1), LocalDate.of(2027, 8, 2));
        assertConflict(preview(f.owner(), capacity), "MODEL_CAPACITY");
        UUID sourceBooking = createBooking(f, "Stock source", LocalDate.of(2027, 9, 1), LocalDate.of(2027, 9, 2));
        addConsumableLine(f.owner(), sourceBooking, bookingVersion(f.owner(), sourceBooking), stockId, "2");
        assertThat(reserve(f.owner(), sourceBooking, bookingVersion(f.owner(), sourceBooking))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> sourceRepair = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/assets/" + box + "/repairs",
                Map.of("referenceOrDescription", "Replace case latch"));
        assertThat(sourceRepair.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertReservationStatus(f, sourceBooking, "ATTENTION_REQUIRED");
        assertConflict(preview(f.owner(), sourceBooking), "SOURCE_CONTAINER_UNAVAILABLE");
        assertThat(checkout(f, sourceBooking, List.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        UUID repair = jdbc.sql("SELECT id FROM asset_repair WHERE asset_id=:id")
                .param("id", cable)
                .query(UUID.class)
                .single();
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/repairs/" + repair + "/close",
                                Map.of("resultingCondition", "GOOD"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertReservationStatus(f, future, "CONFIRMED");
        UUID sourceRepairId =
                UUID.fromString(json(sourceRepair.getBody()).path("id").asText());
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.POST,
                                "/api/v1/repairs/" + sourceRepairId + "/close",
                                Map.of("resultingCondition", "GOOD"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertReservationStatus(f, sourceBooking, "CONFIRMED");
    }

    @Test
    void phase10LostRestorationRecordsPhysicalReturnAndDestroyedLifecycleIsTerminal() {
        Fixture f = fixture();
        UUID box = createAsset(f, createSerializedModel(f, "Restore case", true), "Case");
        UUID asset = createAsset(f, "Found asset");
        addExactRequirement(f, box, asset);
        place(f, asset, box);
        UUID booking = returnedContainers(f, List.of(box));
        UUID audit = startAudit(f, box);
        ResponseEntity<String> result = completeForReview(f, audit, box, true, false);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID finding = UUID.fromString(
                json(result.getBody()).path("findings").get(0).path("id").asText());
        jdbc.sql("UPDATE physical_asset SET lifecycle_state='LOST' WHERE id=:id")
                .param("id", asset)
                .update();
        assertThat(resolveForReview(f, finding, "FOUND_AND_RETURNED", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("SELECT lifecycle_state FROM physical_asset WHERE id=:id")
                        .param("id", asset)
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM asset_state_history WHERE asset_id=:id AND previous_value='LOST' AND new_value='ACTIVE'")
                        .param("id", asset)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // A manifest discrepancy creates a second immutable observation. Reviewing it clears the event.
        JsonNode remaining = json(
                exchange(f.owner(), HttpMethod.GET, "/api/v1/findings", null).getBody());
        for (JsonNode item : remaining) {
            if (item.path("assetId").asText().equals(asset.toString()))
                assertThat(resolveForReview(
                                        f, UUID.fromString(item.path("id").asText()), "FOUND_AND_RETURNED", Map.of())
                                .getStatusCode())
                        .isEqualTo(HttpStatus.OK);
        }
        assertThat(json(exchange(f.owner(), HttpMethod.GET, "/api/v1/bookings/" + booking, null)
                                .getBody())
                        .path("status")
                        .asText())
                .isEqualTo("COMPLETED");
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.PUT,
                                "/api/v1/assets/" + asset + "/lifecycle",
                                Map.of("lifecycleState", "DESTROYED", "reason", "Beyond repair"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.PUT,
                                "/api/v1/assets/" + asset + "/lifecycle",
                                Map.of("lifecycleState", "LOST", "reason", "Attempt to escape terminal state"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(
                                f.owner(),
                                HttpMethod.PUT,
                                "/api/v1/assets/" + asset + "/lifecycle",
                                Map.of("lifecycleState", "ACTIVE", "reason", "Attempt to restore"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> completeForReview(Fixture f, UUID audit, UUID box, boolean missing, boolean sealed) {
        return exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/audits/" + audit + "/complete",
                Map.of(
                        "operationId",
                        UUID.randomUUID(),
                        "containerCode",
                        publicCode(box),
                        "confirmMissing",
                        missing,
                        "sealConfirmed",
                        sealed));
    }

    private ResponseEntity<String> resolveForReview(Fixture f, UUID finding, String action, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(extra);
        body.put("operationId", UUID.randomUUID());
        body.put("action", action);
        return exchange(f.owner(), HttpMethod.POST, "/api/v1/findings/" + finding + "/resolutions", body);
    }

    private UUID returnedContainers(Fixture f, List<UUID> containers) {
        UUID booking = createBooking(f, "Audit return", LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 2));
        for (UUID container : containers)
            assertThat(addContainerLine(f.owner(), booking, bookingVersion(f.owner(), booking), container)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        assertThat(reserve(f.owner(), booking, bookingVersion(f.owner(), booking))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(checkout(f, booking, List.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (UUID container : containers)
            assertThat(exchange(
                                    f.owner(),
                                    HttpMethod.POST,
                                    "/api/v1/bookings/" + booking + "/check-in/assets/" + container,
                                    Map.of("mutationId", UUID.randomUUID()))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        return booking;
    }

    private String publicCode(UUID asset) {
        return jdbc.sql("SELECT public_code FROM physical_asset WHERE id=:id")
                .param("id", asset)
                .query(String.class)
                .single();
    }

    private UUID auditTask(UUID container) {
        return jdbc.sql("SELECT id FROM audit_task WHERE container_asset_id=:id")
                .param("id", container)
                .query(UUID.class)
                .single();
    }

    private UUID startAudit(Fixture f, UUID container) {
        ResponseEntity<String> response = exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/audits/tasks/" + auditTask(container) + "/start",
                Map.of("containerCode", publicCode(container)));
        assertThat(response.getStatusCode()).withFailMessage(response.getBody()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(json(response.getBody()).path("id").asText());
    }

    private ResponseEntity<String> checkout(Fixture f, UUID booking, List<UUID> selected) {
        return exchange(
                f.owner(),
                HttpMethod.POST,
                "/api/v1/bookings/" + booking + "/checkout",
                Map.of(
                        "mutationId",
                        UUID.randomUUID(),
                        "expectedVersion",
                        bookingVersion(f.owner(), booking),
                        "selectedAssetIds",
                        selected));
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

package io.kellermann.tarpeisto.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.model.Organization;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.PermissionTestSupport;
import io.kellermann.tarpeisto.security.PermissionTestSupport.AuthenticatedSession;
import io.kellermann.tarpeisto.service.OrganizationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL-facing acceptance coverage for Phase 4 location, containment and stock places. */
class LocationContainmentControllerIntegrationTests extends AbstractIntegrationTest {
    private static final String PASSWORD = "CorrectHorseBattery1";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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

    @Test
    void deepNestingHasEffectivePathAndOuterMoveChangesDescendantPath() {
        Fixture fixture = fixture();
        UUID hq = location(fixture, "HQ", null);
        UUID shelf = location(fixture, "Shelf A", hq);
        UUID otherShelf = location(fixture, "Shelf B", hq);
        UUID pallet = asset(fixture, "Pallet", true);
        UUID caseId = asset(fixture, "Case", true);
        UUID accessPoint = asset(fixture, "AP", false);
        assertThat(move(fixture, pallet, shelf, null, version(fixture, pallet)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(move(fixture, caseId, null, pallet, version(fixture, caseId)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(move(fixture, accessPoint, null, caseId, version(fixture, accessPoint))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(path(fixture, accessPoint)).contains("HQ", "Shelf A", "Pallet", "Case", "AP");
        assertThat(move(fixture, pallet, otherShelf, null, version(fixture, pallet))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(path(fixture, accessPoint))
                .contains("HQ", "Shelf B", "Pallet", "Case", "AP")
                .doesNotContain("Shelf A");
    }

    @Test
    void rejectsSelfIndirectAndCrossOrganizationHierarchyReferencesWithoutDisclosure() {
        Fixture fixture = fixture();
        UUID root = location(fixture, "HQ", null);
        UUID child = location(fixture, "Room", root);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.PUT,
                                "/api/v1/locations/" + root,
                                Map.of(
                                        "name",
                                        "HQ",
                                        "parentLocationId",
                                        root.toString(),
                                        "expectedVersion",
                                        locationVersion(fixture, root)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.PUT,
                                "/api/v1/locations/" + root,
                                Map.of(
                                        "name",
                                        "HQ",
                                        "parentLocationId",
                                        child.toString(),
                                        "expectedVersion",
                                        locationVersion(fixture, root)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        Fixture other = fixture();
        assertThat(exchange(other.owner(), HttpMethod.GET, "/api/v1/locations/" + root, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
                                other.owner(),
                                HttpMethod.POST,
                                "/api/v1/locations",
                                Map.of("name", "Foreign", "parentLocationId", root.toString()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewerAndOperatorCannotMutateLocationsOrPlacement() {
        Fixture fixture = fixture();
        UUID location = location(fixture, "HQ", null);
        UUID asset = asset(fixture, "AP", false);
        for (OrganizationRole role : List.of(OrganizationRole.OPERATOR_AUDITOR, OrganizationRole.VIEWER)) {
            assertThat(exchange(fixture.session(role), HttpMethod.POST, "/api/v1/locations", Map.of("name", "Denied"))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange(
                                    fixture.session(role),
                                    HttpMethod.PUT,
                                    "/api/v1/assets/" + asset + "/placement",
                                    Map.of(
                                            "locationId",
                                            location.toString(),
                                            "expectedVersion",
                                            version(fixture, asset)))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void rejectsNonContainerParentsStaleMovesAndArchiveWhenInventoryRemains() {
        Fixture fixture = fixture();
        UUID location = location(fixture, "HQ", null);
        UUID ordinary = asset(fixture, "Ordinary", false);
        UUID child = asset(fixture, "Child", false);
        assertThat(move(fixture, child, null, ordinary, version(fixture, child)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        long current = version(fixture, child);
        assertThat(move(fixture, child, location, null, current).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(move(fixture, child, null, null, current).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/locations/" + location + "/archive",
                                Map.of("expectedVersion", locationVersion(fixture, location)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        UUID container = asset(fixture, "Container", true);
        assertThat(move(fixture, child, null, container, version(fixture, child))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(fixture.owner(), HttpMethod.POST, "/api/v1/assets/" + container + "/archive", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsPhysicalSelfAndIndirectContainmentCycles() {
        Fixture fixture = fixture();
        UUID outer = asset(fixture, "Outer", true);
        UUID inner = asset(fixture, "Inner", true);
        assertThat(move(fixture, outer, null, outer, version(fixture, outer)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(move(fixture, inner, null, outer, version(fixture, inner)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(move(fixture, outer, null, inner, version(fixture, outer)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void transfersConsumablesBetweenLocationAndContainerPlaces() {
        Fixture fixture = fixture();
        UUID location = location(fixture, "Stores", null);
        UUID container = asset(fixture, "Flightcase", true);
        UUID model = quantityModel(fixture, "Gaffer tape");
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/asset-models/" + model + "/consumable-stock/receive",
                                Map.of("locationId", location.toString(), "quantity", new BigDecimal("10")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var transfer = exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models/" + model + "/consumable-stock/transfer",
                Map.of(
                        "sourceLocationId",
                        location.toString(),
                        "destinationContainerAssetId",
                        container.toString(),
                        "quantity",
                        new BigDecimal("4")));
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transfer.getBody())
                .contains("\"locationId\":\"" + location + "\"", "\"containerAssetId\":\"" + container + "\"");
        assertThat(exchange(fixture.owner(), HttpMethod.POST, "/api/v1/assets/" + container + "/archive", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/locations/" + location + "/archive",
                                Map.of("expectedVersion", locationVersion(fixture, location)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void retainedZeroContainerBalanceBlocksDisablingContainmentButNotLocationArchive() {
        Fixture fixture = fixture();
        UUID category = id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "Container category-" + UUID.randomUUID(), "color", "#112233")));
        UUID containerModel = id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        "Container model-" + UUID.randomUUID(),
                        "categoryId",
                        category.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        true)));
        UUID container = id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models/" + containerModel + "/assets",
                Map.of("individualName", "Case", "values", List.of())));
        UUID stockModel = quantityModel(fixture, "Tape");
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/asset-models/" + stockModel + "/consumable-stock/receive",
                                Map.of("containerAssetId", container.toString(), "quantity", 1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/asset-models/" + stockModel + "/consumable-stock/consume",
                                Map.of("containerAssetId", container.toString(), "quantity", 1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.PUT,
                                "/api/v1/asset-models/" + containerModel + "/can-contain-assets",
                                Map.of("canContainAssets", false))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        UUID emptyLocation = location(fixture, "Empty after issue", null);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/asset-models/" + stockModel + "/consumable-stock/receive",
                                Map.of("locationId", emptyLocation.toString(), "quantity", 1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/asset-models/" + stockModel + "/consumable-stock/consume",
                                Map.of("locationId", emptyLocation.toString(), "quantity", 1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/locations/" + emptyLocation + "/archive",
                                Map.of("expectedVersion", locationVersion(fixture, emptyLocation)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void opposingContainmentMovesSerializeAndNeverPersistACycle() throws Exception {
        Fixture fixture = fixture();
        UUID first = asset(fixture, "First", true);
        UUID second = asset(fixture, "Second", true);
        ResponseEntity<String>[] results = runTogether(
                () -> move(fixture, first, null, second, version(fixture, first)),
                () -> move(fixture, second, null, first, version(fixture, second)));
        long successfulMoves = java.util.Arrays.stream(results)
                .filter(result -> result.getStatusCode().is2xxSuccessful())
                .count();
        assertThat(successfulMoves).isLessThanOrEqualTo(1);
        JsonNode firstPlacement =
                json(exchange(fixture.owner(), HttpMethod.GET, "/api/v1/assets/" + first + "/placement", null)
                        .getBody());
        JsonNode secondPlacement =
                json(exchange(fixture.owner(), HttpMethod.GET, "/api/v1/assets/" + second + "/placement", null)
                        .getBody());
        assertThat(firstPlacement.path("parentContainerAssetId").asString().equals(second.toString())
                        && secondPlacement
                                .path("parentContainerAssetId")
                                .asString()
                                .equals(first.toString()))
                .isFalse();
    }

    @Test
    void receiptRaceWithArchiveNeverStrandsStock() throws Exception {
        Fixture fixture = fixture();
        UUID container = asset(fixture, "Race case", true);
        UUID stockModel = quantityModel(fixture, "Race tape");
        runTogether(
                () -> exchange(
                        fixture.owner(),
                        HttpMethod.POST,
                        "/api/v1/asset-models/" + stockModel + "/consumable-stock/receive",
                        Map.of("containerAssetId", container.toString(), "quantity", 1)),
                () -> exchange(fixture.owner(), HttpMethod.POST, "/api/v1/assets/" + container + "/archive", null));
        JsonNode asset = json(exchange(fixture.owner(), HttpMethod.GET, "/api/v1/assets/" + container, null)
                .getBody());
        JsonNode balances = json(
                exchange(fixture.owner(), HttpMethod.GET, "/api/v1/assets/" + container + "/consumable-stock", null)
                        .getBody());
        boolean hasStock = hasPositiveBalance(balances);
        assertThat(asset.path("archived").asBoolean() && hasStock).isFalse();
    }

    private UUID location(Fixture fixture, String name, UUID parent) {
        return id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/locations",
                parent == null ? Map.of("name", name) : Map.of("name", name, "parentLocationId", parent.toString())));
    }

    private UUID asset(Fixture fixture, String label, boolean container) {
        UUID category = id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "Category-" + UUID.randomUUID(), "color", "#112233")));
        UUID model = id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        label + " model-" + UUID.randomUUID(),
                        "categoryId",
                        category.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        container)));
        return id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models/" + model + "/assets",
                Map.of("individualName", label, "values", List.of())));
    }

    private UUID quantityModel(Fixture fixture, String label) {
        UUID category = id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "Stock category-" + UUID.randomUUID(), "color", "#112233")));
        return id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        label + "-" + UUID.randomUUID(),
                        "categoryId",
                        category.toString(),
                        "trackingMode",
                        "QUANTITY_STOCK",
                        "stockUnitLabel",
                        "roll",
                        "canContainAssets",
                        false)));
    }

    private ResponseEntity<String> move(Fixture fixture, UUID asset, UUID location, UUID parent, long expectedVersion) {
        var body = new java.util.HashMap<String, Object>();
        body.put("expectedVersion", expectedVersion);
        if (location != null) body.put("locationId", location.toString());
        if (parent != null) body.put("parentContainerAssetId", parent.toString());
        return exchange(fixture.owner(), HttpMethod.PUT, "/api/v1/assets/" + asset + "/placement", body);
    }

    private long version(Fixture fixture, UUID asset) {
        return json(exchange(fixture.owner(), HttpMethod.GET, "/api/v1/assets/" + asset + "/placement", null)
                        .getBody())
                .path("version")
                .asLong();
    }

    private long locationVersion(Fixture fixture, UUID location) {
        return json(exchange(fixture.owner(), HttpMethod.GET, "/api/v1/locations/" + location, null)
                        .getBody())
                .path("version")
                .asLong();
    }

    private String path(Fixture fixture, UUID asset) {
        return json(exchange(fixture.owner(), HttpMethod.GET, "/api/v1/assets/" + asset + "/placement", null)
                        .getBody())
                .path("effectivePathText")
                .asText();
    }

    private UUID id(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(json(response.getBody()).path("id").asText());
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new AssertionError("Could not parse JSON", exception);
        }
    }

    private boolean hasPositiveBalance(JsonNode balances) {
        if (!balances.isArray()) return false;
        for (JsonNode balance : balances) {
            if (balance.path("quantity").asDouble() > 0) return true;
        }
        return false;
    }

    private ResponseEntity<String> exchange(AuthenticatedSession session, HttpMethod method, String path, Object body) {
        var headers = session.headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<String>[] runTogether(
            java.util.concurrent.Callable<ResponseEntity<String>> first,
            java.util.concurrent.Callable<ResponseEntity<String>> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ResponseEntity<String>> firstResult = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting to start");
                return first.call();
            });
            Future<ResponseEntity<String>> secondResult = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting to start");
                return second.call();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return new ResponseEntity[] {firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS)};
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Fixture fixture() {
        Organization organization =
                organizationService.ensureOrganizationExists("Location Test Org " + UUID.randomUUID());
        Map<OrganizationRole, AuthenticatedSession> sessions = new EnumMap<>(OrganizationRole.class);
        for (OrganizationRole role : OrganizationRole.values()) {
            User user = new User(
                    UUID.randomUUID(),
                    role.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID(),
                    null,
                    "Test",
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
            return sessions.get(OrganizationRole.OWNER);
        }

        AuthenticatedSession session(OrganizationRole role) {
            return sessions.get(role);
        }
    }
}

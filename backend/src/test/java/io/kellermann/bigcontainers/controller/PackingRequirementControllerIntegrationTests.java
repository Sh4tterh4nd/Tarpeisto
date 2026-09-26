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
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL-facing acceptance coverage for Phase 5 packing requirements and templates. */
class PackingRequirementControllerIntegrationTests extends AbstractIntegrationTest {
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void onlyOwnersAndDeputiesCanMutateAndTenantReferencesDoNotLeak() {
        Fixture fixture = fixture();
        UUID container = container(fixture, "Network case");
        UUID model = serializedModel(fixture, "AP");
        Fixture other = fixture();
        UUID otherModel = serializedModel(other, "Foreign AP");

        Map<String, Object> modelRequirement = modelRequirement(model, 1);
        for (OrganizationRole role : List.of(OrganizationRole.OPERATOR_AUDITOR, OrganizationRole.VIEWER)) {
            assertThat(exchange(
                                    fixture.session(role),
                                    HttpMethod.POST,
                                    "/api/v1/assets/" + container + "/packing-requirements",
                                    modelRequirement)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(exchange(
                                other.owner(),
                                HttpMethod.POST,
                                "/api/v1/assets/" + container + "/packing-requirements",
                                modelRequirement(otherModel, 1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/assets/" + container + "/packing-requirements",
                                modelRequirement(otherModel, 1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void validatesRequirementVariantsQuantitiesAndContainerCapability() {
        Fixture fixture = fixture();
        UUID ordinary = asset(fixture, serializedModel(fixture, "Ordinary model"), "Ordinary");
        UUID container = container(fixture, "Named case");
        UUID serialized = serializedModel(fixture, "Cable");
        UUID quantity = quantityModel(fixture, "Tape");
        UUID cable = asset(fixture, serialized, "Cable 1");

        assertThat(add(fixture, ordinary, modelRequirement(serialized, 1)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(add(
                                fixture,
                                container,
                                Map.of(
                                        "type",
                                        "SPECIFIC_ASSET",
                                        "specificAssetReference",
                                        cable.toString(),
                                        "requiredQuantity",
                                        2))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(add(fixture, container, modelRequirement(serialized, new BigDecimal("1.5")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(add(fixture, container, modelRequirement(serialized, new BigDecimal("2147483648")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(add(fixture, container, modelRequirement(quantity, 1)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(add(fixture, container, consumableRequirement(serialized, 1)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(add(fixture, container, consumableRequirement(quantity, BigDecimal.ZERO))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(add(fixture, container, consumableRequirement(quantity, new BigDecimal("1.125")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aContainerAssetNeedsAnIndividualNameBeforePackingCanBeConfigured() {
        Fixture fixture = fixture();
        UUID model = serializedModel(fixture, category(fixture), "Unnamed case", true);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/asset-models/" + model + "/assets",
                                Map.of("values", List.of()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void previewUsesDirectActiveContentsExactBeforeModelAndSeparateConsumableObservation() {
        Fixture fixture = fixture();
        UUID container = container(fixture, "Cable box");
        UUID otherContainer = container(fixture, "Other box");
        UUID cableModel = serializedModel(fixture, "Interchangeable cable");
        UUID tapeModel = quantityModel(fixture, "Gaffer tape");
        UUID exact = asset(fixture, cableModel, "Exact cable");
        UUID misplaced = asset(fixture, cableModel, "Pinned elsewhere");
        List<UUID> interchangeable = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            interchangeable.add(asset(fixture, cableModel, "Interchangeable " + i));
        }
        UUID nestedContainer = container(fixture, "Nested bag");
        UUID nestedCable = asset(fixture, cableModel, "Nested only");
        UUID inactive = asset(fixture, cableModel, "Lost cable");
        UUID archived = asset(fixture, cableModel, "Archived cable");
        UUID extra = asset(fixture, cableModel, "Unexpected cable");

        UUID exactRequirement = requirementId(add(fixture, container, exactRequirement(exact)));
        UUID modelRequirement = requirementId(add(fixture, container, modelRequirement(cableModel, 5)));
        UUID otherExactRequirement = requirementId(add(fixture, otherContainer, exactRequirement(misplaced)));
        UUID consumableRequirement = requirementId(add(fixture, container, consumableRequirement(tapeModel, 5)));
        assertThat(otherExactRequirement).isNotNull();
        move(fixture, exact, container);
        move(fixture, misplaced, container);
        for (UUID id : interchangeable) move(fixture, id, container);
        move(fixture, nestedContainer, container);
        move(fixture, nestedCable, nestedContainer);
        move(fixture, inactive, container);
        move(fixture, archived, container);
        move(fixture, extra, container);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.PUT,
                                "/api/v1/assets/" + inactive + "/lifecycle",
                                Map.of("lifecycleState", "LOST"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(fixture.owner(), HttpMethod.POST, "/api/v1/assets/" + archived + "/archive", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/asset-models/" + tapeModel + "/consumable-stock/receive",
                                Map.of("containerAssetId", container.toString(), "quantity", 3))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode beforeObservation = preview(fixture, container, Map.of());
        assertThat(beforeObservation.path("complete").asBoolean()).isFalse();
        assertThat(ids(beforeObservation.path("satisfiedRequirementIds"))).contains(exactRequirement, modelRequirement);
        assertThat(ids(beforeObservation.path("missingRequirementIds"))).contains(consumableRequirement);
        assertThat(ids(beforeObservation.path("misplacedAssetIds"))).containsExactly(misplaced);
        assertThat(ids(beforeObservation.path("extraAssetIds"))).containsExactly(nestedContainer, extra);
        assertThat(ids(beforeObservation.path("extraAssetIds")))
                .doesNotContain(nestedCable, inactive, archived, exact)
                .doesNotContainAnyElementsOf(interchangeable);
        assertThat(beforeObservation
                        .path("consumables")
                        .get(0)
                        .path("observedQuantity")
                        .decimalValue())
                .isEqualByComparingTo("3");

        JsonNode observed = preview(fixture, container, Map.of(consumableRequirement.toString(), new BigDecimal("6")));
        assertThat(ids(observed.path("satisfiedRequirementIds"))).contains(consumableRequirement);
        assertThat(observed.path("consumables").get(0).path("observedQuantity").decimalValue())
                .isEqualByComparingTo("6");
        JsonNode persistentStock = json(
                exchange(fixture.owner(), HttpMethod.GET, "/api/v1/assets/" + container + "/consumable-stock", null)
                        .getBody());
        assertThat(persistentStock.get(0).path("quantity").decimalValue()).isEqualByComparingTo("3");
    }

    @Test
    void nestedContentsDoNotSatisfyADirectRequirement() {
        Fixture fixture = fixture();
        UUID container = container(fixture, "Outer case");
        UUID nested = container(fixture, "Inner case");
        UUID model = serializedModel(fixture, "Cable");
        UUID cable = asset(fixture, model, "Nested cable");
        UUID requirement = requirementId(add(fixture, container, modelRequirement(model, 1)));
        move(fixture, nested, container);
        move(fixture, cable, nested);

        JsonNode preview = preview(fixture, container, Map.of());
        assertThat(ids(preview.path("missingRequirementIds"))).containsExactly(requirement);
        assertThat(ids(preview.path("extraAssetIds"))).containsExactly(nested);
    }

    @Test
    void exactAssignmentIsUniqueEvenWhenRequestsRace() throws Exception {
        Fixture fixture = fixture();
        UUID first = container(fixture, "First case");
        UUID second = container(fixture, "Second case");
        UUID model = serializedModel(fixture, "AP");
        UUID exact = asset(fixture, model, "Pinned AP");

        ResponseEntity<String>[] responses = runTogether(
                () -> add(fixture, first, exactRequirement(exact)),
                () -> add(fixture, second, exactRequirement(exact)));
        long created = java.util.Arrays.stream(responses)
                .filter(response -> response.getStatusCode() == HttpStatus.CREATED)
                .count();
        assertThat(created).isEqualTo(1);
        assertThat(java.util.Arrays.stream(responses)
                        .filter(response -> response.getStatusCode() != HttpStatus.CREATED)
                        .findFirst()
                        .orElseThrow()
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void copiedTemplatesAreIndependentAndFailedApplicationRollsBackAllCopiedRows() {
        Fixture fixture = fixture();
        UUID first = container(fixture, "First case");
        UUID second = container(fixture, "Second case");
        UUID model = serializedModel(fixture, "Cable");
        UUID exact = asset(fixture, model, "Template exact");
        UUID template = templateId(createTemplate(fixture, "Cable template"));
        addTemplateRequirement(fixture, template, exactRequirement(exact));
        JsonNode templateAfterModel = addTemplateRequirement(fixture, template, modelRequirement(model, 2));
        UUID templateModelRow = id(templateAfterModel.path("requirements").get(1));

        ResponseEntity<String> firstApply = exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/assets/" + first + "/packing-templates/" + template + "/apply",
                null);
        assertThat(firstApply.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode copied = json(firstApply.getBody());
        UUID copiedExact = id(copied.get(0));
        UUID copiedModel = id(copied.get(1));
        ResponseEntity<String> templateUpdate = exchange(
                fixture.owner(),
                HttpMethod.PUT,
                "/api/v1/packing-template-requirements/" + templateModelRow,
                Map.of(
                        "expectedVersion",
                        templateAfterModel
                                .path("requirements")
                                .get(1)
                                .path("version")
                                .asLong(),
                        "requirement",
                        modelRequirement(model, 4)));
        assertThat(templateUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode copiedList = listRequirements(fixture, first);
        assertThat(copiedList.get(0).path("id").asText()).isEqualTo(copiedExact.toString());
        assertThat(copiedList.get(1).path("id").asText()).isEqualTo(copiedModel.toString());
        assertThat(copiedList.get(1).path("requiredQuantity").decimalValue()).isEqualByComparingTo("2");

        assertThat(add(fixture, second, modelRequirement(model, 1)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> failedApply = exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/assets/" + second + "/packing-templates/" + template + "/apply",
                null);
        assertThat(failedApply.getStatusCode().isError()).isTrue();
        JsonNode secondRequirements = listRequirements(fixture, second);
        assertThat(secondRequirements).hasSize(1);
        assertThat(secondRequirements.get(0).path("type").asText()).isEqualTo("MODEL_QUANTITY");
    }

    @Test
    void staleEditsAndRestoresFailAndRequirementHistoryCannotBeMutated() {
        Fixture fixture = fixture();
        UUID container = container(fixture, "History case");
        UUID model = serializedModel(fixture, "Cable");
        JsonNode created =
                json(add(fixture, container, modelRequirement(model, 1)).getBody());
        UUID requirement = id(created);
        long version = created.path("version").asLong();
        ResponseEntity<String> updated = exchange(
                fixture.owner(),
                HttpMethod.PUT,
                "/api/v1/packing-requirements/" + requirement,
                Map.of("expectedVersion", version, "requirement", modelRequirement(model, 2)));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.PUT,
                                "/api/v1/packing-requirements/" + requirement,
                                Map.of("expectedVersion", version, "requirement", modelRequirement(model, 3)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        long updatedVersion = json(updated.getBody()).path("version").asLong();
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/packing-requirements/" + requirement + "/archive",
                                Map.of("expectedVersion", updatedVersion))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/packing-requirements/" + requirement + "/restore",
                                Map.of("expectedVersion", updatedVersion))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE packing_requirement_history SET action = 'MUTATED' WHERE packing_requirement_id = ?",
                        requirement))
                .isInstanceOf(Exception.class);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT action FROM packing_requirement_history WHERE packing_requirement_id = ? ORDER BY occurred_at",
                        String.class,
                        requirement))
                .contains("CREATED", "UPDATED", "ARCHIVED");
        JsonNode updateDetails = json(jdbcTemplate.queryForObject(
                "SELECT details::text FROM packing_requirement_history WHERE packing_requirement_id = ? AND action = 'UPDATED'",
                String.class,
                requirement));
        assertThat(updateDetails.path("before").path("quantity").decimalValue()).isEqualByComparingTo("1");
        assertThat(updateDetails.path("after").path("quantity").decimalValue()).isEqualByComparingTo("2");
    }

    @Test
    void activeRequirementsBlockContainmentDisableButArchivedRequirementsDoNot() {
        Fixture fixture = fixture();
        UUID category = category(fixture);
        UUID containerModel = serializedModel(fixture, category, "Case model", true);
        UUID container = asset(fixture, containerModel, "Case");
        UUID cableModel = serializedModel(fixture, "Cable");
        JsonNode requirement =
                json(add(fixture, container, modelRequirement(cableModel, 1)).getBody());
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.PUT,
                                "/api/v1/asset-models/" + containerModel + "/can-contain-assets",
                                Map.of("canContainAssets", false))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/packing-requirements/" + id(requirement) + "/archive",
                                Map.of(
                                        "expectedVersion",
                                        requirement.path("version").asLong()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.PUT,
                                "/api/v1/asset-models/" + containerModel + "/can-contain-assets",
                                Map.of("canContainAssets", false))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void activeRequirementsBlockChangingTheReferencedModelsTrackingMode() {
        Fixture fixture = fixture();
        UUID container = container(fixture, "Tracking case");
        UUID model = serializedModel(fixture, "Convertible cable");
        requirementId(add(fixture, container, modelRequirement(model, 1)));

        assertThat(exchange(
                                fixture.owner(),
                                HttpMethod.PUT,
                                "/api/v1/asset-models/" + model + "/tracking-mode",
                                Map.of("trackingMode", "QUANTITY_STOCK", "stockUnitLabel", "roll"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void previewIsNotCompleteWhenAContainerHasAnUnrequiredExtra() {
        Fixture fixture = fixture();
        UUID container = container(fixture, "Complete case");
        UUID model = serializedModel(fixture, "Cable");
        UUID required = asset(fixture, model, "Required cable");
        UUID extra = asset(fixture, model, "Extra cable");
        requirementId(add(fixture, container, exactRequirement(required)));
        move(fixture, required, container);
        move(fixture, extra, container);

        JsonNode preview = preview(fixture, container, Map.of());
        assertThat(ids(preview.path("extraAssetIds"))).containsExactly(extra);
        assertThat(preview.path("complete").asBoolean()).isFalse();
    }

    private ResponseEntity<String> add(Fixture fixture, UUID container, Map<String, Object> body) {
        return exchange(
                fixture.owner(), HttpMethod.POST, "/api/v1/assets/" + container + "/packing-requirements", body);
    }

    private JsonNode preview(Fixture fixture, UUID container, Map<String, BigDecimal> observations) {
        return json(exchange(
                        fixture.owner(),
                        HttpMethod.POST,
                        "/api/v1/assets/" + container + "/packing-preview",
                        Map.of("observedConsumableQuantities", observations))
                .getBody());
    }

    private JsonNode listRequirements(Fixture fixture, UUID container) {
        ResponseEntity<String> response = exchange(
                fixture.owner(), HttpMethod.GET, "/api/v1/assets/" + container + "/packing-requirements", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(response.getBody());
    }

    private ResponseEntity<String> createTemplate(Fixture fixture, String name) {
        return exchange(fixture.owner(), HttpMethod.POST, "/api/v1/packing-templates", Map.of("name", name));
    }

    private JsonNode addTemplateRequirement(Fixture fixture, UUID template, Map<String, Object> requirement) {
        ResponseEntity<String> response = exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/packing-templates/" + template + "/requirements",
                requirement);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(response.getBody());
    }

    private void move(Fixture fixture, UUID asset, UUID parentContainer) {
        ResponseEntity<String> response = exchange(
                fixture.owner(),
                HttpMethod.PUT,
                "/api/v1/assets/" + asset + "/placement",
                Map.of(
                        "parentContainerAssetId",
                        parentContainer.toString(),
                        "expectedVersion",
                        placementVersion(fixture, asset)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private long placementVersion(Fixture fixture, UUID asset) {
        return json(exchange(fixture.owner(), HttpMethod.GET, "/api/v1/assets/" + asset + "/placement", null)
                        .getBody())
                .path("version")
                .asLong();
    }

    private UUID category(Fixture fixture) {
        return id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "Category-" + UUID.randomUUID(), "color", "#112233")));
    }

    private UUID serializedModel(Fixture fixture, String name) {
        return serializedModel(fixture, category(fixture), name, false);
    }

    private UUID serializedModel(Fixture fixture, UUID category, String name, boolean canContainAssets) {
        return id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        name + "-" + UUID.randomUUID(),
                        "categoryId",
                        category.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        canContainAssets)));
    }

    private UUID quantityModel(Fixture fixture, String name) {
        return id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        name + "-" + UUID.randomUUID(),
                        "categoryId",
                        category(fixture).toString(),
                        "trackingMode",
                        "QUANTITY_STOCK",
                        "stockUnitLabel",
                        "roll",
                        "canContainAssets",
                        false)));
    }

    private UUID container(Fixture fixture, String name) {
        return asset(fixture, serializedModel(fixture, name + " model"), name, true);
    }

    private UUID asset(Fixture fixture, UUID model, String name) {
        return asset(fixture, model, name, false);
    }

    private UUID asset(Fixture fixture, UUID model, String name, boolean container) {
        if (container) {
            UUID category = category(fixture);
            model = serializedModel(fixture, category, name + " container model", true);
        }
        return id(exchange(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models/" + model + "/assets",
                Map.of("individualName", name, "values", List.of())));
    }

    private static Map<String, Object> exactRequirement(UUID asset) {
        return Map.of("type", "SPECIFIC_ASSET", "specificAssetReference", asset.toString(), "requiredQuantity", 1);
    }

    private static Map<String, Object> modelRequirement(UUID model, Number quantity) {
        return Map.of("type", "MODEL_QUANTITY", "assetModelId", model.toString(), "requiredQuantity", quantity);
    }

    private static Map<String, Object> consumableRequirement(UUID model, Number quantity) {
        return Map.of("type", "CONSUMABLE_QUANTITY", "assetModelId", model.toString(), "requiredQuantity", quantity);
    }

    private UUID requirementId(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return id(json(response.getBody()));
    }

    private UUID templateId(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return id(json(response.getBody()));
    }

    private UUID id(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return id(json(response.getBody()));
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.path("id").asText());
    }

    private static List<UUID> ids(JsonNode nodes) {
        List<UUID> values = new ArrayList<>();
        nodes.forEach(node -> values.add(UUID.fromString(node.asText())));
        return values;
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new AssertionError("Could not parse JSON", exception);
        }
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
                organizationService.ensureOrganizationExists("Packing test org " + UUID.randomUUID());
        Map<OrganizationRole, AuthenticatedSession> sessions = new EnumMap<>(OrganizationRole.class);
        for (OrganizationRole role : OrganizationRole.values()) {
            User user = new User(
                    UUID.randomUUID(),
                    role.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID(),
                    null,
                    "Packing test user",
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

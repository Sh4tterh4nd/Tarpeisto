package io.kellermann.tarpeisto.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.model.Organization;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.ModelCustomFieldOptionRepository;
import io.kellermann.tarpeisto.repository.ModelCustomFieldRepository;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.PermissionTestSupport;
import io.kellermann.tarpeisto.security.PermissionTestSupport.AuthenticatedSession;
import io.kellermann.tarpeisto.service.OrganizationService;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Covers specification section 7: model-defined unit field definitions are Owner/Deputy
 * administered, live only on a {@code SERIALIZED_ASSET} model, have organization-and-model-scoped
 * unique names while active, and dropdown options can only be attached to a {@code DROPDOWN}
 * field.
 */
class ModelCustomFieldControllerIntegrationTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBattery1";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private ModelCustomFieldRepository modelCustomFieldRepository;

    @Autowired
    private ModelCustomFieldOptionRepository modelCustomFieldOptionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @ParameterizedTest
    @EnumSource(
            value = OrganizationRole.class,
            names = {"OPERATOR_AUDITOR", "VIEWER"})
    void creatingACustomFieldIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture);

        var response = exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields",
                Map.of("name", "Serial Number", "dataType", "STRING"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void everyRoleCanListCustomFields() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture);
        createField(fixture, modelId, "Serial Number", "STRING");

        for (OrganizationRole role : OrganizationRole.values()) {
            var response = exchange(
                    fixture.sessionFor(role),
                    HttpMethod.GET,
                    "/api/v1/asset-models/" + modelId + "/custom-fields",
                    null);
            assertThat(response.getStatusCode()).as("role %s", role).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as("role %s", role).contains("Serial Number");
        }
    }

    @Test
    void ownerCanCreateStringDropdownAndDateFields() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture);

        var stringResponse = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields",
                Map.of("name", "Serial Number", "dataType", "STRING"));
        var dropdownResponse = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields",
                Map.of("name", "Radio Mode", "dataType", "DROPDOWN"));
        var dateResponse = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields",
                Map.of("name", "Commissioned On", "dataType", "DATE"));

        assertThat(stringResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(dropdownResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(dateResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aQuantityStockModelCannotDefineCustomFields() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);
        UUID modelId = createQuantityModel(fixture, categoryId);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields",
                Map.of("name", "Batch Number", "dataType", "STRING"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aDuplicateFieldNameOnTheSameModelIsRejected() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture);
        createField(fixture, modelId, "Serial Number", "STRING");

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields",
                Map.of("name", "serial number", "dataType", "STRING"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void archivingAFieldDoesNotDeleteItAndRestoreReversesIt() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture);
        UUID fieldId = createField(fixture, modelId, "Serial Number", "STRING");

        var archiveResponse = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields/" + fieldId + "/archive",
                null);
        assertThat(archiveResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(modelCustomFieldRepository.findById(fieldId).orElseThrow().isArchived())
                .isTrue();

        var restoreResponse = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields/" + fieldId + "/restore",
                null);
        assertThat(restoreResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(modelCustomFieldRepository.findById(fieldId).orElseThrow().isArchived())
                .isFalse();
    }

    @Test
    void optionsCanOnlyBeAddedToADropdownField() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture);
        UUID stringFieldId = createField(fixture, modelId, "Serial Number", "STRING");

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields/" + stringFieldId + "/options",
                Map.of("value", "2.4GHz"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void dropdownOptionsCanBeCreatedListedAndArchived() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture);
        UUID fieldId = createField(fixture, modelId, "Radio Mode", "DROPDOWN");

        var createResponse = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields/" + fieldId + "/options",
                Map.of("value", "2.4GHz"));
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID optionId = UUID.fromString(extractField(createResponse.getBody(), "id"));

        var listResponse = exchange(
                fixture.sessionFor(OrganizationRole.VIEWER),
                HttpMethod.GET,
                "/api/v1/asset-models/" + modelId + "/custom-fields/" + fieldId + "/options",
                null);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).contains("2.4GHz");

        var archiveResponse = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields/" + fieldId + "/options/" + optionId + "/archive",
                null);
        assertThat(archiveResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(modelCustomFieldOptionRepository
                        .findById(optionId)
                        .orElseThrow()
                        .isArchived())
                .isTrue();
    }

    @Test
    void aDuplicateOptionValueOnTheSameFieldIsRejected() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture);
        UUID fieldId = createField(fixture, modelId, "Radio Mode", "DROPDOWN");
        exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields/" + fieldId + "/options",
                Map.of("value", "2.4GHz"));

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields/" + fieldId + "/options",
                Map.of("value", "2.4ghz"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aCustomFieldFromAnotherOrganizationIsNotVisibleOrMutable() {
        Fixture organizationA = fixtureWithAllRoles();
        Fixture organizationB = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(organizationA);
        UUID fieldId = createField(organizationA, modelId, "Serial Number", "STRING");

        var archiveResponse = exchange(
                organizationB.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields/" + fieldId + "/archive",
                null);

        assertThat(archiveResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private UUID createCategory(Fixture fixture) {
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "Networking-" + UUID.randomUUID(), "color", "#112233"));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private UUID createSerializedModel(Fixture fixture) {
        UUID categoryId = createCategory(fixture);
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        "UniFi AP-HD-" + UUID.randomUUID(),
                        "categoryId",
                        categoryId.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        false));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private UUID createQuantityModel(Fixture fixture, UUID categoryId) {
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        "Gaffer tape-" + UUID.randomUUID(),
                        "categoryId",
                        categoryId.toString(),
                        "trackingMode",
                        "QUANTITY_STOCK",
                        "stockUnitLabel",
                        "roll",
                        "canContainAssets",
                        false));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private UUID createField(Fixture fixture, UUID modelId, String name, String dataType) {
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields",
                Map.of("name", name, "dataType", dataType));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private org.springframework.http.ResponseEntity<String> exchange(
            AuthenticatedSession session, HttpMethod method, String path, Object body) {
        var headers = session.headers();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String extractField(String json, String fieldName) {
        var matcher = java.util.regex.Pattern.compile("\"" + fieldName + "\":\"([^\"]+)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private Fixture fixtureWithAllRoles() {
        Organization organization =
                organizationService.ensureOrganizationExists("Model Field Test Org " + UUID.randomUUID());
        Map<OrganizationRole, AuthenticatedSession> sessionsByRole = new EnumMap<>(OrganizationRole.class);
        for (OrganizationRole role : OrganizationRole.values()) {
            User user = createUser(organization, role.name().toLowerCase(Locale.ROOT), role);
            sessionsByRole.put(role, PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD));
        }
        return new Fixture(organization, sessionsByRole);
    }

    private User createUser(Organization organization, String usernamePrefix, OrganizationRole role) {
        var now = clock.instant();
        User user = new User(
                UUID.randomUUID(),
                usernamePrefix + "-" + UUID.randomUUID(),
                null,
                "Test User " + usernamePrefix,
                passwordEncoder.encode(PASSWORD),
                true,
                now);
        userRepository.save(user);
        membershipRepository.save(
                new OrganizationMembership(UUID.randomUUID(), organization.getId(), user.getId(), role, now));
        return user;
    }

    private record Fixture(Organization organization, Map<OrganizationRole, AuthenticatedSession> sessionsByRole) {

        AuthenticatedSession sessionFor(OrganizationRole role) {
            return sessionsByRole.get(role);
        }
    }
}

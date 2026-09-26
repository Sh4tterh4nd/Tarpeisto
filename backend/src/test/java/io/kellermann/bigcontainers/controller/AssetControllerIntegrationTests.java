package io.kellermann.bigcontainers.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.bigcontainers.AbstractIntegrationTest;
import io.kellermann.bigcontainers.model.AssetCode;
import io.kellermann.bigcontainers.model.AssetCodeValidation;
import io.kellermann.bigcontainers.model.Organization;
import io.kellermann.bigcontainers.model.OrganizationMembership;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.model.User;
import io.kellermann.bigcontainers.repository.AssetRepository;
import io.kellermann.bigcontainers.repository.AssetStateChangeRepository;
import io.kellermann.bigcontainers.repository.OrganizationMembershipRepository;
import io.kellermann.bigcontainers.repository.UserRepository;
import io.kellermann.bigcontainers.security.PermissionTestSupport;
import io.kellermann.bigcontainers.security.PermissionTestSupport.AuthenticatedSession;
import io.kellermann.bigcontainers.service.OrganizationService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Covers specification sections 6.2, 7, 8, and 9, and ADR-0002: Owner/Deputy administer assets,
 * Operator/Auditor and Viewer cannot mutate them, only a {@code SERIALIZED_ASSET} model may have
 * assets, every unit gets a distinct checked public code, bulk creation assigns consecutive
 * model-local unit numbers, all active custom fields are required at creation, metadata
 * incompleteness appears and clears correctly, dropdown option validation, lifecycle/condition
 * history, archive/lifecycle filtering, and manual code lookup distinguishing a transcription error
 * from an unknown code.
 */
class AssetControllerIntegrationTests extends AbstractIntegrationTest {

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
    private AssetRepository assetRepository;

    @Autowired
    private AssetStateChangeRepository assetStateChangeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @ParameterizedTest
    @EnumSource(
            value = OrganizationRole.class,
            names = {"OPERATOR_AUDITOR", "VIEWER"})
    void creatingAnAssetIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "UniFi AP-HD", false);

        var response = exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets",
                Map.of("values", List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(
            value = OrganizationRole.class,
            names = {"OPERATOR_AUDITOR", "VIEWER"})
    void bulkCreatingAssetsIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "LAN 10m", false);

        var response = exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets/bulk",
                Map.of("count", 3));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void everyRoleCanReadAssets() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "UniFi AP-HD", false);
        UUID assetId = extractId(bulkCreate(fixture, modelId, 1).getBody(), 0);

        for (OrganizationRole role : OrganizationRole.values()) {
            var listResponse = exchange(
                    fixture.sessionFor(role), HttpMethod.GET, "/api/v1/asset-models/" + modelId + "/assets", null);
            assertThat(listResponse.getStatusCode()).as("role %s", role).isEqualTo(HttpStatus.OK);

            var getResponse = exchange(fixture.sessionFor(role), HttpMethod.GET, "/api/v1/assets/" + assetId, null);
            assertThat(getResponse.getStatusCode()).as("role %s", role).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void aQuantityStockModelCannotHaveAssetsCreated() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(fixture, "Gaffer tape 50 mm");

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets",
                Map.of("values", List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aQuantityStockModelCannotBeBulkCreated() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(fixture, "Gaffer tape 50 mm");

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets/bulk",
                Map.of("count", 5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aContainerCapableModelRequiresAnIndividualNameToCreateAndCannotBeBulkCreated() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "Pelican 1510", true);

        var withoutName = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets",
                Map.of("values", List.of()));
        assertThat(withoutName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var withName = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets",
                Map.of("individualName", "Mobile Network Box", "values", List.of()));
        assertThat(withName.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var bulk = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets/bulk",
                Map.of("count", 2));
        assertThat(bulk.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void creatingAnAssetRequiresEveryActiveCustomFieldValue() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "UniFi AP-HD", false);
        UUID fieldId = createStringField(fixture, modelId, "Serial Number");

        var withoutValue = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets",
                Map.of("values", List.of()));
        assertThat(withoutValue.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var withValue = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets",
                Map.of("values", List.of(Map.of("fieldId", fieldId.toString(), "stringValue", "SN-001"))));
        assertThat(withValue.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(withValue.getBody()).contains("\"metadataIncomplete\":false");
    }

    @Test
    void bulkCreationAssignsConsecutiveUnitNumbers() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "LAN 10m", false);

        var response = bulkCreate(fixture, modelId, 5);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<Integer> unitNumbers = extractIntFields(response.getBody(), "unitNumber");
        assertThat(unitNumbers).containsExactlyInAnyOrder(1, 2, 3, 4, 5);

        var second = bulkCreate(fixture, modelId, 3);
        List<Integer> secondNumbers = extractIntFields(second.getBody(), "unitNumber");
        assertThat(secondNumbers).containsExactlyInAnyOrder(6, 7, 8);
    }

    @Test
    void everyCreatedAssetReceivesADistinctValidCheckedCode() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "LAN 10m", false);

        var response = bulkCreate(fixture, modelId, 20);
        List<String> codes = extractStringFields(response.getBody(), "publicCode");
        assertThat(codes).hasSize(20);
        assertThat(new HashSet<>(codes)).hasSize(20);
        for (String code : codes) {
            assertThat(AssetCode.validate(code)).isInstanceOf(AssetCodeValidation.Valid.class);
        }
    }

    @Test
    void bulkCreatedUnitsAreMetadataIncompleteUntilValuesAreSet() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "UniFi AP-HD", false);
        UUID fieldId = createStringField(fixture, modelId, "Serial Number");

        UUID assetId = extractId(bulkCreate(fixture, modelId, 1).getBody(), 0);
        var afterCreate =
                exchange(fixture.sessionFor(OrganizationRole.OWNER), HttpMethod.GET, "/api/v1/assets/" + assetId, null);
        assertThat(afterCreate.getBody()).contains("\"metadataIncomplete\":true");

        var setValues = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/assets/" + assetId + "/values",
                List.of(Map.of("fieldId", fieldId.toString(), "stringValue", "SN-999")));
        assertThat(setValues.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setValues.getBody()).contains("\"metadataIncomplete\":false");
    }

    @Test
    void addingAFieldToAModelWithExistingUnitsMarksThemMetadataIncomplete() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "UniFi AP-HD", false);
        UUID assetId = extractId(bulkCreate(fixture, modelId, 1).getBody(), 0);

        var beforeField =
                exchange(fixture.sessionFor(OrganizationRole.OWNER), HttpMethod.GET, "/api/v1/assets/" + assetId, null);
        assertThat(beforeField.getBody()).contains("\"metadataIncomplete\":false");

        createStringField(fixture, modelId, "Serial Number");

        var afterField =
                exchange(fixture.sessionFor(OrganizationRole.OWNER), HttpMethod.GET, "/api/v1/assets/" + assetId, null);
        assertThat(afterField.getBody()).contains("\"metadataIncomplete\":true");
    }

    @Test
    void dropdownOptionMustBelongToTheCorrectField() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "UniFi AP-HD", false);
        UUID fieldA = createDropdownField(fixture, modelId, "Radio Mode");
        createOption(fixture, modelId, fieldA, "2.4GHz");
        UUID fieldB = createDropdownField(fixture, modelId, "Mount Type");
        UUID optionOfB = createOption(fixture, modelId, fieldB, "Ceiling");

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets",
                Map.of(
                        "values",
                        List.of(
                                Map.of("fieldId", fieldA.toString(), "optionId", optionOfB.toString()),
                                Map.of("fieldId", fieldB.toString(), "optionId", optionOfB.toString()))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void dropdownOptionMustBeActive() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "UniFi AP-HD", false);
        UUID fieldId = createDropdownField(fixture, modelId, "Radio Mode");
        UUID optionId = createOption(fixture, modelId, fieldId, "2.4GHz");
        exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields/" + fieldId + "/options/" + optionId + "/archive",
                null);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets",
                Map.of("values", List.of(Map.of("fieldId", fieldId.toString(), "optionId", optionId.toString()))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aStringFieldRejectsAnOptionReference() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "UniFi AP-HD", false);
        UUID stringFieldId = createStringField(fixture, modelId, "Serial Number");

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets",
                Map.of(
                        "values",
                        List.of(Map.of(
                                "fieldId",
                                stringFieldId.toString(),
                                "optionId",
                                UUID.randomUUID().toString()))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lifecycleAndConditionTransitionsAreRecordedInHistory() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "UniFi AP-HD", false);
        UUID assetId = extractId(bulkCreate(fixture, modelId, 1).getBody(), 0);

        var damaged = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/assets/" + assetId + "/condition",
                Map.of("condition", "DAMAGED", "reason", "Dropped during transport"));
        assertThat(damaged.getStatusCode()).isEqualTo(HttpStatus.OK);

        var lost = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/assets/" + assetId + "/lifecycle",
                Map.of("lifecycleState", "LOST"));
        assertThat(lost.getStatusCode()).isEqualTo(HttpStatus.OK);

        var restored = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/assets/" + assetId + "/lifecycle",
                Map.of("lifecycleState", "ACTIVE"));
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restored.getBody()).contains("\"lifecycleState\":\"ACTIVE\"");

        var history = exchange(
                fixture.sessionFor(OrganizationRole.OPERATOR_AUDITOR),
                HttpMethod.GET,
                "/api/v1/assets/" + assetId + "/history",
                null);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).contains("\"changeType\":\"CONDITION\"").contains("\"changeType\":\"LIFECYCLE\"");
        assertThat(assetStateChangeRepository.findAllByOrganizationIdAndAssetIdOrderByChangedAtDesc(
                        fixture.organization().getId(), assetId))
                .hasSize(3);
    }

    @Test
    void archivedAndLostAssetsAreExcludedFromDefaultListingButFindableWithTheFilter() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "LAN 10m", false);
        var created = bulkCreate(fixture, modelId, 3);
        UUID activeId = extractId(created.getBody(), 0);
        UUID lostId = extractId(created.getBody(), 1);
        UUID archivedId = extractId(created.getBody(), 2);

        exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/assets/" + lostId + "/lifecycle",
                Map.of("lifecycleState", "LOST"));
        exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/assets/" + archivedId + "/archive",
                null);

        var defaultListing = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.GET,
                "/api/v1/asset-models/" + modelId + "/assets",
                null);
        List<String> defaultIds = extractStringFields(defaultListing.getBody(), "id");
        assertThat(defaultIds).contains(activeId.toString());
        assertThat(defaultIds).doesNotContain(lostId.toString(), archivedId.toString());

        var fullListing = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.GET,
                "/api/v1/asset-models/" + modelId + "/assets?includeInactive=true",
                null);
        List<String> fullIds = extractStringFields(fullListing.getBody(), "id");
        assertThat(fullIds).contains(activeId.toString(), lostId.toString(), archivedId.toString());
    }

    @Test
    void manualCodeLookupDistinguishesATranscriptionErrorFromAnUnknownCode() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "LAN 10m", false);
        UUID assetId = extractId(bulkCreate(fixture, modelId, 1).getBody(), 0);
        String publicCode = extractStringFields(
                        exchange(
                                        fixture.sessionFor(OrganizationRole.OWNER),
                                        HttpMethod.GET,
                                        "/api/v1/assets/" + assetId,
                                        null)
                                .getBody(),
                        "publicCode")
                .get(0);

        var found = exchange(
                fixture.sessionFor(OrganizationRole.OPERATOR_AUDITOR),
                HttpMethod.GET,
                "/api/v1/assets/by-code/" + publicCode,
                null);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);

        // ADR-0002 test vector: single-symbol substitution of the valid code 7K3MXY.
        var transcriptionError = exchange(
                fixture.sessionFor(OrganizationRole.OPERATOR_AUDITOR),
                HttpMethod.GET,
                "/api/v1/assets/by-code/7K3MXZ",
                null);
        assertThat(transcriptionError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transcriptionError.getBody()).contains("CHECKSUM_MISMATCH");

        // ADR-0002 test vector: a well-formed, checksum-valid code that this organization never
        // generated - "unknown code", not a transcription error.
        var unknownCode = exchange(
                fixture.sessionFor(OrganizationRole.OPERATOR_AUDITOR),
                HttpMethod.GET,
                "/api/v1/assets/by-code/PQRSTJ",
                null);
        assertThat(unknownCode.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anAssetFromAnotherOrganizationIsNotVisibleOrMutable() {
        Fixture organizationA = fixtureWithAllRoles();
        Fixture organizationB = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(organizationA, "UniFi AP-HD", false);
        UUID assetId = extractId(bulkCreate(organizationA, modelId, 1).getBody(), 0);

        var getResponse = exchange(
                organizationB.sessionFor(OrganizationRole.OWNER), HttpMethod.GET, "/api/v1/assets/" + assetId, null);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var archiveResponse = exchange(
                organizationB.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/assets/" + assetId + "/archive",
                null);
        assertThat(archiveResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private org.springframework.http.ResponseEntity<String> bulkCreate(Fixture fixture, UUID modelId, int count) {
        return exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets/bulk",
                Map.of("count", count));
    }

    private UUID createCategory(Fixture fixture) {
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "Networking-" + UUID.randomUUID(), "color", "#112233"));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private UUID createSerializedModel(Fixture fixture, String name, boolean canContainAssets) {
        UUID categoryId = createCategory(fixture);
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        name + "-" + UUID.randomUUID(),
                        "categoryId",
                        categoryId.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        canContainAssets));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private UUID createQuantityModel(Fixture fixture, String name) {
        UUID categoryId = createCategory(fixture);
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        name + "-" + UUID.randomUUID(),
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

    private UUID createStringField(Fixture fixture, UUID modelId, String name) {
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields",
                Map.of("name", name, "dataType", "STRING"));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private UUID createDropdownField(Fixture fixture, UUID modelId, String name) {
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields",
                Map.of("name", name, "dataType", "DROPDOWN"));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private UUID createOption(Fixture fixture, UUID modelId, UUID fieldId, String value) {
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields/" + fieldId + "/options",
                Map.of("value", value));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private org.springframework.http.ResponseEntity<String> exchange(
            AuthenticatedSession session, HttpMethod method, String path, Object body) {
        var headers = session.headers();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String extractField(String json, String fieldName) {
        var matcher = Pattern.compile("\"" + fieldName + "\":\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static UUID extractId(String json, int index) {
        return UUID.fromString(extractStringFields(json, "id").get(index));
    }

    private static List<String> extractStringFields(String json, String fieldName) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"" + fieldName + "\":\"([^\"]*)\"").matcher(json);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static List<Integer> extractIntFields(String json, String fieldName) {
        List<Integer> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"" + fieldName + "\":(-?\\d+)").matcher(json);
        while (matcher.find()) {
            values.add(Integer.valueOf(matcher.group(1)));
        }
        return values;
    }

    private Fixture fixtureWithAllRoles() {
        Organization organization = organizationService.ensureOrganizationExists("Asset Test Org " + UUID.randomUUID());
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

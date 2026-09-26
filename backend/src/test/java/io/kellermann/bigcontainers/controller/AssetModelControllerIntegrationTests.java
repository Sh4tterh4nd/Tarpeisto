package io.kellermann.bigcontainers.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.bigcontainers.AbstractIntegrationTest;
import io.kellermann.bigcontainers.model.Organization;
import io.kellermann.bigcontainers.model.OrganizationMembership;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.model.User;
import io.kellermann.bigcontainers.repository.AssetModelRepository;
import io.kellermann.bigcontainers.repository.OrganizationMembershipRepository;
import io.kellermann.bigcontainers.repository.UserRepository;
import io.kellermann.bigcontainers.security.PermissionTestSupport;
import io.kellermann.bigcontainers.security.PermissionTestSupport.AuthenticatedSession;
import io.kellermann.bigcontainers.service.OrganizationService;
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
 * Covers specification section 6: Owner/Deputy administer asset models, Operator/Auditor and
 * Viewer cannot mutate them, a quantity-tracked model cannot be container-capable, category
 * selection is organization-scoped and rejects an archived category, and cross-organization
 * records are indistinguishable from missing.
 */
class AssetModelControllerIntegrationTests extends AbstractIntegrationTest {

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
    private AssetModelRepository assetModelRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @ParameterizedTest
    @EnumSource(
            value = OrganizationRole.class,
            names = {"OPERATOR_AUDITOR", "VIEWER"})
    void creatingAnAssetModelIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);

        var response = exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        "UniFi AP-HD",
                        "categoryId",
                        categoryId.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void everyRoleCanReadAssetModels() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);
        UUID modelId = createSerializedModel(fixture, categoryId, "UniFi AP-HD", false);

        for (OrganizationRole role : OrganizationRole.values()) {
            var response = exchange(fixture.sessionFor(role), HttpMethod.GET, "/api/v1/asset-models/" + modelId, null);
            assertThat(response.getStatusCode()).as("role %s", role).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void ownerCanCreateASerializedContainerCapableModel() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        "Pelican 1510",
                        "categoryId",
                        categoryId.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"canContainAssets\":true");
    }

    @Test
    void deputyCanCreateAQuantityStockModelWithAStockUnitLabel() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.DEPUTY),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name", "Gaffer tape 50 mm",
                        "categoryId", categoryId.toString(),
                        "trackingMode", "QUANTITY_STOCK",
                        "stockUnitLabel", "roll",
                        "canContainAssets", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"stockUnitLabel\":\"roll\"");
    }

    @Test
    void aQuantityStockModelCannotBeContainerCapable() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name", "Gaffer tape 50 mm",
                        "categoryId", categoryId.toString(),
                        "trackingMode", "QUANTITY_STOCK",
                        "stockUnitLabel", "roll",
                        "canContainAssets", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aQuantityStockModelRequiresAStockUnitLabel() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        "Gaffer tape 50 mm",
                        "categoryId",
                        categoryId.toString(),
                        "trackingMode",
                        "QUANTITY_STOCK",
                        "canContainAssets",
                        false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anArchivedCategoryCannotBeSelectedForANewModel() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);
        exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/categories/" + categoryId + "/archive",
                null);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        "UniFi AP-HD",
                        "categoryId",
                        categoryId.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aCategoryFromAnotherOrganizationCannotBeSelected() {
        Fixture organizationA = fixtureWithAllRoles();
        Fixture organizationB = fixtureWithAllRoles();
        UUID categoryFromB = createCategory(organizationB);

        var response = exchange(
                organizationA.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        "UniFi AP-HD",
                        "categoryId",
                        categoryFromB.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void creatingAnAssetModelWithADuplicateNameIsRejected() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);
        createSerializedModel(fixture, categoryId, "UniFi AP-HD", false);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        "unifi ap-hd",
                        "categoryId",
                        categoryId.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void disablingContainerCapabilityOnASerializedModelSucceeds() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);
        UUID modelId = createSerializedModel(fixture, categoryId, "Pelican 1510", true);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/asset-models/" + modelId + "/can-contain-assets",
                Map.of("canContainAssets", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assetModelRepository.findById(modelId).orElseThrow().isCanContainAssets())
                .isFalse();
    }

    @Test
    void changingTrackingModeToQuantityStockWhileCustomFieldsExistIsRejected() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);
        UUID modelId = createSerializedModel(fixture, categoryId, "UniFi AP-HD", false);
        exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/custom-fields",
                Map.of("name", "Serial Number", "dataType", "STRING"));

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/asset-models/" + modelId + "/tracking-mode",
                Map.of("trackingMode", "QUANTITY_STOCK", "stockUnitLabel", "roll"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(assetModelRepository.findById(modelId).orElseThrow().isQuantityTracked())
                .isFalse();
    }

    @Test
    void archivingAndRestoringAnAssetModelSucceedsForOwner() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture);
        UUID modelId = createSerializedModel(fixture, categoryId, "UniFi AP-HD", false);

        var archiveResponse = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/archive",
                null);
        assertThat(archiveResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(assetModelRepository.findById(modelId).orElseThrow().isArchived())
                .isTrue();

        var restoreResponse = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/restore",
                null);
        assertThat(restoreResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(assetModelRepository.findById(modelId).orElseThrow().isArchived())
                .isFalse();
    }

    @Test
    void anAssetModelFromAnotherOrganizationIsNotVisibleOrMutable() {
        Fixture organizationA = fixtureWithAllRoles();
        Fixture organizationB = fixtureWithAllRoles();
        UUID categoryId = createCategory(organizationA);
        UUID modelId = createSerializedModel(organizationA, categoryId, "UniFi AP-HD", false);

        var getResponse = exchange(
                organizationB.sessionFor(OrganizationRole.OWNER),
                HttpMethod.GET,
                "/api/v1/asset-models/" + modelId,
                null);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var archiveResponse = exchange(
                organizationB.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/archive",
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

    private UUID createSerializedModel(Fixture fixture, UUID categoryId, String name, boolean canContainAssets) {
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        name,
                        "categoryId",
                        categoryId.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        canContainAssets));
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
                organizationService.ensureOrganizationExists("Asset Model Test Org " + UUID.randomUUID());
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

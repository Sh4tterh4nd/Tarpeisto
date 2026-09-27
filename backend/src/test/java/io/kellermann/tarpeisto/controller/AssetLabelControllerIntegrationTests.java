package io.kellermann.tarpeisto.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.AssetModel;
import io.kellermann.tarpeisto.model.Category;
import io.kellermann.tarpeisto.model.Organization;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.AssetModelRepository;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.CategoryRepository;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.PermissionTestSupport;
import io.kellermann.tarpeisto.security.PermissionTestSupport.AuthenticatedSession;
import io.kellermann.tarpeisto.service.OrganizationService;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
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

/** PostgreSQL/MVC coverage for organization-scoped, read-only Phase 6 label downloads. */
class AssetLabelControllerIntegrationTests extends AbstractIntegrationTest {

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
    private CategoryRepository categoryRepository;

    @Autowired
    private AssetModelRepository assetModelRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @ParameterizedTest
    @EnumSource(OrganizationRole.class)
    void everyPermanentRoleCanGenerateReadOnlyLabelDocuments(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();
        UUID assetId = createAsset(fixture.organization(), "7K3MXY");

        assertThat(post(
                                fixture.sessionFor(role),
                                "/api/v1/asset-labels/ptouch-csv",
                                Map.of("assetIds", List.of(assetId.toString())))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post(fixture.sessionFor(role), "/api/v1/asset-labels/calibration", Map.of("format", "A4_70X36_24"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void csvHasCanonicalColumnsAndCrossOrganizationAssetsAreNotDisclosed() {
        Fixture organizationA = fixtureWithAllRoles();
        Fixture organizationB = fixtureWithAllRoles();
        UUID ownAssetId = createAsset(organizationA.organization(), "7K3MXY");
        UUID foreignAssetId = createAsset(organizationB.organization(), "91TRQJ");

        var ownResponse = post(
                organizationA.sessionFor(OrganizationRole.VIEWER),
                "/api/v1/asset-labels/ptouch-csv",
                Map.of("assetIds", List.of(ownAssetId.toString())));
        assertThat(ownResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownResponse.getBody())
                .startsWith("model_name,asset_name,asset_code,category,category_color,qr_value\r\n")
                .contains("7K3MXY");

        assertThat(post(
                                organizationA.sessionFor(OrganizationRole.VIEWER),
                                "/api/v1/asset-labels/ptouch-csv",
                                Map.of("assetIds", List.of(foreignAssetId.toString())))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsDuplicateMissingAndOverLimitAssetSelections() {
        Fixture fixture = fixtureWithAllRoles();
        UUID assetId = createAsset(fixture.organization(), "7K3MXY");
        AuthenticatedSession viewer = fixture.sessionFor(OrganizationRole.VIEWER);

        assertThat(post(
                                viewer,
                                "/api/v1/asset-labels/ptouch-csv",
                                Map.of("assetIds", List.of(assetId.toString(), assetId.toString())))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(
                                viewer,
                                "/api/v1/asset-labels/ptouch-csv",
                                Map.of("assetIds", List.of(UUID.randomUUID().toString())))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(
                                viewer,
                                "/api/v1/asset-labels/ptouch-csv",
                                Map.of(
                                        "assetIds",
                                        java.util.Collections.nCopies(
                                                501, UUID.randomUUID().toString())))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void csvPreservesRequestedOrderAndAllowsArchivedAssetsToBeReprinted() {
        Fixture fixture = fixtureWithAllRoles();
        UUID activeAssetId = createAsset(fixture.organization(), "7K3MXY");
        UUID archivedAssetId = createAsset(fixture.organization(), "91TRQJ");
        Asset archivedAsset = assetRepository.findById(archivedAssetId).orElseThrow();
        archivedAsset.archive(clock.instant());
        assetRepository.saveAndFlush(archivedAsset);

        var response = post(
                fixture.sessionFor(OrganizationRole.VIEWER),
                "/api/v1/asset-labels/ptouch-csv",
                Map.of("assetIds", List.of(archivedAssetId.toString(), activeAssetId.toString())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().indexOf("91TRQJ"))
                .isLessThan(response.getBody().indexOf("7K3MXY"));
    }

    private UUID createAsset(Organization organization, String publicCode) {
        var now = clock.instant();
        Category category =
                new Category(UUID.randomUUID(), organization.getId(), "Cables-" + UUID.randomUUID(), "#112233", now);
        categoryRepository.save(category);
        AssetModel model = new AssetModel(
                UUID.randomUUID(),
                organization.getId(),
                "Cable model-" + UUID.randomUUID(),
                null,
                category.getId(),
                null,
                TrackingMode.SERIALIZED_ASSET,
                null,
                null,
                false,
                now);
        assetModelRepository.save(model);
        Asset asset = new Asset(
                UUID.randomUUID(), organization.getId(), model.getId(), publicCode, 1, "Name, \"quoted\"", null, now);
        assetRepository.save(asset);
        return asset.getId();
    }

    private org.springframework.http.ResponseEntity<String> post(
            AuthenticatedSession session, String path, Object body) {
        var headers = session.headers();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private Fixture fixtureWithAllRoles() {
        Organization organization =
                organizationService.ensureOrganizationExists("Labels Test Org " + UUID.randomUUID());
        Map<OrganizationRole, AuthenticatedSession> sessions = new EnumMap<>(OrganizationRole.class);
        for (OrganizationRole role : OrganizationRole.values()) {
            User user = createUser(organization, role);
            sessions.put(role, PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD));
        }
        return new Fixture(organization, sessions);
    }

    private User createUser(Organization organization, OrganizationRole role) {
        var now = clock.instant();
        String prefix = role.name().toLowerCase(Locale.ROOT);
        User user = new User(
                UUID.randomUUID(),
                prefix + "-label-" + UUID.randomUUID(),
                null,
                "Test " + prefix,
                passwordEncoder.encode(PASSWORD),
                true,
                now);
        userRepository.save(user);
        membershipRepository.save(
                new OrganizationMembership(UUID.randomUUID(), organization.getId(), user.getId(), role, now));
        return user;
    }

    private record Fixture(Organization organization, Map<OrganizationRole, AuthenticatedSession> sessions) {

        AuthenticatedSession sessionFor(OrganizationRole role) {
            return sessions.get(role);
        }
    }
}

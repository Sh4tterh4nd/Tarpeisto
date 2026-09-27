package io.kellermann.bigcontainers.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.bigcontainers.AbstractIntegrationTest;
import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.AssetModel;
import io.kellermann.bigcontainers.model.Category;
import io.kellermann.bigcontainers.model.Organization;
import io.kellermann.bigcontainers.model.OrganizationMembership;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.model.PackingRequirement;
import io.kellermann.bigcontainers.model.PackingRequirementType;
import io.kellermann.bigcontainers.model.TrackingMode;
import io.kellermann.bigcontainers.model.User;
import io.kellermann.bigcontainers.repository.AssetModelRepository;
import io.kellermann.bigcontainers.repository.AssetRepository;
import io.kellermann.bigcontainers.repository.CategoryRepository;
import io.kellermann.bigcontainers.repository.OrganizationMembershipRepository;
import io.kellermann.bigcontainers.repository.PackingRequirementRepository;
import io.kellermann.bigcontainers.repository.UserRepository;
import io.kellermann.bigcontainers.security.PermissionTestSupport;
import io.kellermann.bigcontainers.security.PermissionTestSupport.AuthenticatedSession;
import io.kellermann.bigcontainers.service.OrganizationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/** PostgreSQL/MVC coverage for the Phase 11 tenant-safe packing-sheet endpoint. */
class PackingSheetControllerIntegrationTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBattery1";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private OrganizationMembershipRepository memberships;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private AssetModelRepository models;

    @Autowired
    private AssetRepository assets;

    @Autowired
    private PackingRequirementRepository requirements;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @ParameterizedTest
    @EnumSource(OrganizationRole.class)
    void everyPermanentRoleCanDownloadOwnContainerSheet(OrganizationRole role) {
        Fixture fixture = fixture();
        UUID assetId = createAsset(fixture.organization(), true, "7K3MXY");

        var response = get(fixture.sessionFor(role), assetId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment");
        assertThat(response.getBody()).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @org.junit.jupiter.api.Test
    void foreignAndNonContainerAssetsAreNotRendered() {
        Fixture own = fixture();
        Fixture foreign = fixture();
        UUID foreignAsset = createAsset(foreign.organization(), true, "91TRQJ");
        UUID nonContainer = createAsset(own.organization(), false, "91TRQJ");

        assertThat(get(own.sessionFor(OrganizationRole.VIEWER), foreignAsset).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        var foreignResponse = get(own.sessionFor(OrganizationRole.VIEWER), foreignAsset);
        var missingResponse = get(own.sessionFor(OrganizationRole.VIEWER), UUID.randomUUID());
        assertThat(missingResponse.getStatusCode()).isEqualTo(foreignResponse.getStatusCode());
        String foreignBody = new String(foreignResponse.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        String missingBody = new String(missingResponse.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(foreignBody).contains("Asset not found.");
        assertThat(missingBody).contains("Asset not found.");
        assertThat(get(own.sessionFor(OrganizationRole.VIEWER), nonContainer).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @org.junit.jupiter.api.Test
    void anonymousDownloadRequiresAuthentication() {
        var response =
                restTemplate.getForEntity("/api/v1/assets/" + UUID.randomUUID() + "/packing-sheet.pdf", byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @org.junit.jupiter.api.Test
    void rendersActiveDirectRequirementsAndChildIdentityWithoutExpandingGrandchildren() throws Exception {
        Fixture fixture = fixture();
        UUID parentId = createAsset(fixture.organization(), true, "7K3MXY");
        UUID childId = createAsset(fixture.organization(), true, "91TRQJ");
        UUID grandchildId = createAsset(fixture.organization(), true, "A72KQ5");
        UUID exactId = createAsset(fixture.organization(), false, "M39TX1");
        var child = assets.findById(childId).orElseThrow();
        child.rename("Direct child", clock.instant());
        child.moveTo(null, parentId, clock.instant());
        assets.saveAndFlush(child);
        var grandchild = assets.findById(grandchildId).orElseThrow();
        grandchild.rename("Grandchild should not appear", clock.instant());
        grandchild.moveTo(null, childId, clock.instant());
        assets.saveAndFlush(grandchild);
        var exact = assets.findById(exactId).orElseThrow();
        exact.rename("Configured Gateway", clock.instant());
        assets.saveAndFlush(exact);
        var exactModel = models.findById(exact.getAssetModelId()).orElseThrow();
        AssetModel stockModel = new AssetModel(
                UUID.randomUUID(),
                fixture.organization().getId(),
                "Gaffer tape 50 mm",
                null,
                exactModel.getCategoryId(),
                null,
                TrackingMode.QUANTITY_STOCK,
                "rolls",
                null,
                false,
                clock.instant());
        models.saveAndFlush(stockModel);
        var archived = new PackingRequirement(
                UUID.randomUUID(),
                fixture.organization().getId(),
                parentId,
                PackingRequirementType.MODEL_QUANTITY,
                child.getAssetModelId(),
                null,
                new BigDecimal("99"),
                3,
                clock.instant());
        archived.archive(clock.instant());
        requirements.saveAllAndFlush(List.of(
                new PackingRequirement(
                        UUID.randomUUID(),
                        fixture.organization().getId(),
                        parentId,
                        PackingRequirementType.MODEL_QUANTITY,
                        exact.getAssetModelId(),
                        null,
                        BigDecimal.TEN,
                        0,
                        clock.instant()),
                new PackingRequirement(
                        UUID.randomUUID(),
                        fixture.organization().getId(),
                        parentId,
                        PackingRequirementType.SPECIFIC_ASSET,
                        null,
                        exactId,
                        BigDecimal.ONE,
                        1,
                        clock.instant()),
                new PackingRequirement(
                        UUID.randomUUID(),
                        fixture.organization().getId(),
                        parentId,
                        PackingRequirementType.CONSUMABLE_QUANTITY,
                        stockModel.getId(),
                        null,
                        new BigDecimal("2.125"),
                        2,
                        clock.instant()),
                archived,
                new PackingRequirement(
                        UUID.randomUUID(),
                        fixture.organization().getId(),
                        childId,
                        PackingRequirementType.MODEL_QUANTITY,
                        child.getAssetModelId(),
                        null,
                        new BigDecimal("123"),
                        0,
                        clock.instant())));
        var response = get(fixture.sessionFor(OrganizationRole.VIEWER), parentId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (var document = Loader.loadPDF(response.getBody())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains(
                            "10 x " + exactModel.getName(),
                            "2.125 rolls Gaffer tape 50 mm",
                            "M39TX1",
                            "Configured Gateway",
                            "Direct child",
                            "91TRQJ");
            assertThat(text).doesNotContain("Grandchild should not appear", "A72KQ5", "99 x", "123 x");
        }
    }

    @org.junit.jupiter.api.Test
    void exactRequiredChildAppearsOncePerHalfWhileOtherCurrentChildrenRemainVisible() throws Exception {
        Fixture fixture = fixture();
        UUID parentId = createAsset(fixture.organization(), true, "7K3MXY");
        UUID exactChildId = createAsset(fixture.organization(), true, "91TRQJ");
        UUID otherChildId = createAsset(fixture.organization(), true, "A72KQ5");
        for (UUID childId : List.of(exactChildId, otherChildId)) {
            var child = assets.findById(childId).orElseThrow();
            child.rename(
                    childId.equals(exactChildId) ? "Exact required child" : "Other current child", clock.instant());
            child.moveTo(null, parentId, clock.instant());
            assets.saveAndFlush(child);
        }
        requirements.saveAndFlush(new PackingRequirement(
                UUID.randomUUID(),
                fixture.organization().getId(),
                parentId,
                PackingRequirementType.SPECIFIC_ASSET,
                null,
                exactChildId,
                BigDecimal.ONE,
                0,
                clock.instant()));
        var response = get(fixture.sessionFor(OrganizationRole.VIEWER), parentId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (var document = Loader.loadPDF(response.getBody())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            var page = document.getPage(0);
            float halfHeight = page.getMediaBox().getHeight() / 2;
            var stripper = new org.apache.pdfbox.text.PDFTextStripperByArea();
            stripper.addRegion(
                    "top",
                    new java.awt.geom.Rectangle2D.Float(0, 0, page.getMediaBox().getWidth(), halfHeight));
            stripper.addRegion(
                    "bottom",
                    new java.awt.geom.Rectangle2D.Float(
                            0, halfHeight, page.getMediaBox().getWidth(), halfHeight));
            stripper.extractRegions(page);
            for (String region : List.of("top", "bottom")) {
                String text = stripper.getTextForRegion(region);
                assertThat(text)
                        .contains("Specific:", "Nested containers:", "Exact required child", "Other current child");
                assertThat(text.split("91TRQJ", -1).length - 1).isEqualTo(1);
                assertThat(text.split("A72KQ5", -1).length - 1).isEqualTo(1);
                assertThat(text.split("Exact required child", -1).length - 1).isEqualTo(1);
            }
        }
    }

    private UUID createAsset(Organization organization, boolean canContainAssets, String publicCode) {
        var now = clock.instant();
        Category category =
                new Category(UUID.randomUUID(), organization.getId(), "Cat-" + UUID.randomUUID(), "#112233", now);
        categories.save(category);
        AssetModel model = new AssetModel(
                UUID.randomUUID(),
                organization.getId(),
                "Model-" + UUID.randomUUID(),
                null,
                category.getId(),
                null,
                TrackingMode.SERIALIZED_ASSET,
                null,
                null,
                canContainAssets,
                now);
        models.save(model);
        Asset asset = new Asset(
                UUID.randomUUID(), organization.getId(), model.getId(), publicCode, 1, "Container", null, now);
        assets.saveAndFlush(asset);
        return asset.getId();
    }

    private org.springframework.http.ResponseEntity<byte[]> get(AuthenticatedSession session, UUID assetId) {
        return restTemplate.exchange(
                "/api/v1/assets/" + assetId + "/packing-sheet.pdf",
                HttpMethod.GET,
                new HttpEntity<>(session.headers()),
                byte[].class);
    }

    private Fixture fixture() {
        Organization organization = organizations.ensureOrganizationExists("Packing sheet test " + UUID.randomUUID());
        Map<OrganizationRole, AuthenticatedSession> sessions = new EnumMap<>(OrganizationRole.class);
        for (OrganizationRole role : OrganizationRole.values()) {
            String prefix = role.name().toLowerCase(Locale.ROOT);
            var now = clock.instant();
            User user = new User(
                    UUID.randomUUID(),
                    prefix + "-packing-" + UUID.randomUUID(),
                    null,
                    "Test " + prefix,
                    passwordEncoder.encode(PASSWORD),
                    true,
                    now);
            users.save(user);
            memberships.save(
                    new OrganizationMembership(UUID.randomUUID(), organization.getId(), user.getId(), role, now));
            sessions.put(role, PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD));
        }
        return new Fixture(organization, sessions);
    }

    private record Fixture(Organization organization, Map<OrganizationRole, AuthenticatedSession> sessions) {
        AuthenticatedSession sessionFor(OrganizationRole role) {
            return sessions.get(role);
        }
    }
}

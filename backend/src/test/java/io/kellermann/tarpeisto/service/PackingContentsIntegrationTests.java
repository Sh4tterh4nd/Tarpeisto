package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.PackingConflictException;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.PackingContentStatus;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import io.kellermann.tarpeisto.model.SealState;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.security.TemporaryAccessContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class PackingContentsIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private PackingContentsService contents;

    @Autowired
    private PackingRequirementService packing;

    @Autowired
    private AssetPlacementService placements;

    @Autowired
    private AssetModelService models;

    @Autowired
    private AssetService assets;

    @Autowired
    private ConsumableStockService stocks;

    @Autowired
    private AssetSealService seals;

    @Autowired
    private AuditService audits;

    @Autowired
    private org.springframework.boot.resttestclient.TestRestTemplate http;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwords;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private EntityManager entities;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private java.time.Clock clock;

    @Test
    void globallyOrdersProblemsBeforeMatchedAndReturnsAllBoundedCollectionsExactlyOnce() {
        var owner = owner();
        var box = box(owner);
        var unit = model(owner, "Cable", TrackingMode.SERIALIZED_ASSET);
        var rows = assets.createBulk(owner, unit, 105, null);
        for (var row : rows) placements.move(owner, row.id(), null, box, 0);
        packing.add(owner, box, PackingRequirementType.MODEL_QUANTITY, unit, null, new BigDecimal("104"));
        for (int i = 0; i < 105; i++) {
            var stockModel = model(owner, "Tape " + i, TrackingMode.QUANTITY_STOCK);
            packing.add(
                    owner, box, PackingRequirementType.CONSUMABLE_QUANTITY, stockModel, null, new BigDecimal("1.125"));
            stocks.receive(owner, stockModel, box, new BigDecimal("1.125"), null);
        }
        entities.flush();
        entities.clear();
        var first = contents.get(owner, box, null);
        assertThat(first.assets()).hasSize(100);
        assertThat(first.requirements()).hasSize(100);
        assertThat(first.consumables()).hasSize(100);
        assertThat(first.totalAssetCount()).isEqualTo(105);
        assertThat(first.totalRequirementCount()).isEqualTo(106);
        assertThat(first.totalConsumableCount()).isEqualTo(105);
        assertThat(first.assets().getFirst().status()).isEqualTo(PackingContentStatus.EXTRA);
        assertThat(first.matchedCount()).isEqualTo(104);
        var second = contents.get(owner, box, first.nextCursor());
        assertThat(second.assets()).hasSize(5);
        assertThat(second.requirements()).hasSize(6);
        assertThat(second.consumables()).hasSize(5);
        assertThat(second.nextCursor()).isNull();
        var ids = new HashSet<UUID>();
        first.assets().forEach(row -> assertThat(ids.add(row.asset().assetId())).isTrue());
        second.assets()
                .forEach(row -> assertThat(ids.add(row.asset().assetId())).isTrue());
        assertThat(ids).hasSize(105);
        assertThat(first.consumables())
                .allSatisfy(row -> assertThat(row.quantity()).isEqualByComparingTo("1.125"));
    }

    @Test
    void exactAssignmentsAndGlobalPinsRemainAuthoritativeAndInactivePhysicalContentsVisible() {
        var owner = owner();
        var box = box(owner);
        var other = box(owner);
        var unit = model(owner, "Cable", TrackingMode.SERIALIZED_ASSET);
        var rows = assets.createBulk(owner, unit, 3, null);
        for (var row : rows) placements.move(owner, row.id(), null, box, 0);
        var exact = packing.add(
                owner,
                box,
                PackingRequirementType.SPECIFIC_ASSET,
                null,
                rows.get(0).id().toString(),
                BigDecimal.ONE);
        packing.add(
                owner,
                other,
                PackingRequirementType.SPECIFIC_ASSET,
                null,
                rows.get(1).id().toString(),
                BigDecimal.ONE);
        packing.add(owner, box, PackingRequirementType.MODEL_QUANTITY, unit, null, BigDecimal.ONE);
        entities.flush();
        assets.changeLifecycleState(
                owner, rows.get(2).id(), io.kellermann.tarpeisto.model.LifecycleState.RETIRED, "Retired");
        entities.flush();
        entities.clear();
        var view = contents.get(owner, box, null);
        assertThat(view.complete()).isFalse();
        assertThat(view.assets())
                .anySatisfy(row -> {
                    assertThat(row.asset().assetId()).isEqualTo(rows.get(0).id());
                    assertThat(row.requirementId()).isEqualTo(exact.id());
                    assertThat(row.status()).isEqualTo(PackingContentStatus.MATCHED);
                })
                .anySatisfy(row -> assertThat(row.status()).isEqualTo(PackingContentStatus.MISPLACED))
                .anySatisfy(row -> assertThat(row.status()).isEqualTo(PackingContentStatus.INACTIVE));
        assertThat(view.inactiveCount()).isOne();
    }

    @Test
    void cursorRejectsChangedPackingAndAnotherTenantAndMissingStockRetainsUnit() {
        var owner = owner();
        var box = box(owner);
        var unit = model(owner, "Cable", TrackingMode.SERIALIZED_ASSET);
        var rows = assets.createBulk(owner, unit, 101, null);
        for (var row : rows) placements.move(owner, row.id(), null, box, 0);
        var requirement =
                packing.add(owner, box, PackingRequirementType.MODEL_QUANTITY, unit, null, new BigDecimal("101"));
        var tape = model(owner, "Tape", TrackingMode.QUANTITY_STOCK);
        packing.add(owner, box, PackingRequirementType.CONSUMABLE_QUANTITY, tape, null, new BigDecimal("3"));
        entities.flush();
        entities.clear();
        var page = contents.get(owner, box, null);
        assertThat(page.requirements()).anySatisfy(row -> {
            assertThat(row.assetModelId()).isEqualTo(tape);
            assertThat(row.unitLabel()).isEqualTo("rolls");
            assertThat(row.missingQuantity()).isEqualByComparingTo("3");
        });
        assertThatThrownBy(() -> contents.get(owner(), box, page.nextCursor())).isInstanceOf(NotFoundException.class);
        packing.update(
                owner,
                requirement.id(),
                requirement.version(),
                PackingRequirementType.MODEL_QUANTITY,
                unit,
                null,
                new BigDecimal("100"));
        entities.flush();
        entities.clear();
        assertThatThrownBy(() -> contents.get(owner, box, page.nextCursor()))
                .isInstanceOf(PackingConflictException.class);
        assertThat(contents.get(owner, box, null).extraCount()).isOne();
    }

    @Test
    void physicalApplyIsVersionGuardedAttributedAndNeverVerifies() {
        var owner = owner();
        var box = box(owner);
        seals.setSealable(owner, box, true);
        entities.flush();
        entities.clear();
        long version = placements.get(owner, box).version();
        seals.applySeal(owner, box, version);
        entities.flush();
        entities.clear();
        assertThat(assetRepository.findById(box).orElseThrow().getSealState()).isEqualTo(SealState.APPLIED);
        assertThat(assetRepository.findById(box).orElseThrow().getLastVerifiedAt())
                .isNull();
        seals.applySeal(owner, box, version);
        entities.flush();
        assertThat(appliedCount(box)).isOne();
        var code = assets.get(owner, box).publicCode();
        var audit = audits.launchContainerAudit(owner, box, code, UUID.randomUUID());
        audits.complete(owner, audit.id(), UUID.randomUUID(), code, true, true);
        // Standalone completion records verification; verified physical seal promotion normally occurs on event return.
        assetRepository.findById(box).orElseThrow().verifySeal(clock.instant());
        entities.flush();
        entities.clear();
        assertThat(assetRepository.findById(box).orElseThrow().getLastVerifiedAt())
                .isNotNull();
        int appliedBeforeFresh = appliedCount(box);
        String completedFacts = jdbc.sql(
                        "SELECT state || ':' || completed_at || ':' || completed_by_user_id FROM container_audit WHERE id=:id")
                .param("id", audit.id())
                .query(String.class)
                .single();
        assertThatThrownBy(() -> seals.applySeal(owner, box, version)).isInstanceOf(PackingConflictException.class);
        assertThat(jdbc.sql("SELECT current_attempt FROM container_audit WHERE id=:id")
                        .param("id", audit.id())
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(assetRepository.findById(box).orElseThrow().getSealState()).isEqualTo(SealState.VERIFIED);
        assertThat(appliedCount(box)).isEqualTo(appliedBeforeFresh);
        seals.applySeal(owner, box, placements.get(owner, box).version());
        entities.flush();
        entities.clear();
        assertThat(assetRepository.findById(box).orElseThrow().getSealState()).isEqualTo(SealState.APPLIED);
        assertThat(appliedCount(box)).isEqualTo(appliedBeforeFresh + 1);
        assertThat(assetRepository.findById(box).orElseThrow().getLastVerifiedAt())
                .isNull();
        assertThat(jdbc.sql(
                                "SELECT state || ':' || completed_at || ':' || completed_by_user_id FROM container_audit WHERE id=:id")
                        .param("id", audit.id())
                        .query(String.class)
                        .single())
                .isEqualTo(completedFacts);
        assertThat(jdbc.sql("SELECT current_attempt FROM container_audit WHERE id=:id")
                        .param("id", audit.id())
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(jdbc.sql("SELECT state FROM audit_task WHERE container_asset_id=:id")
                        .param("id", box)
                        .query(String.class)
                        .single())
                .isEqualTo("READY");
    }

    @Test
    void readsAllowPermanentViewerButMutationsRequireManagerAndTemporaryAccessIsDenied() {
        var owner = owner();
        var box = box(owner);
        var viewer = new TarpeistoPrincipal(
                owner.userId(), owner.username(), "Viewer", owner.organizationId(), OrganizationRole.VIEWER);
        assertThat(contents.get(viewer, box, null).totalAssetCount()).isZero();
        assertThatThrownBy(() -> seals.applySeal(viewer, box, 0)).isInstanceOf(AccessDeniedException.class);
        var temporary = new TarpeistoPrincipal(
                owner.userId(),
                owner.username(),
                "Helper",
                owner.organizationId(),
                OrganizationRole.OPERATOR_AUDITOR,
                new TemporaryAccessContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        Instant.parse("2099-01-01T00:00:00Z")));
        assertThatThrownBy(() -> contents.get(temporary, box, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> seals.applySeal(temporary, box, 0)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> seals.applySeal(owner(), box, 0)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void httpReadsRolesCsrfExpectedVersionAndTenantBoundaryAreAuthoritative() {
        var owner = owner();
        var box = box(owner);
        seals.setSealable(owner, box, true);
        jdbc.sql("UPDATE app_user SET password_hash=:hash WHERE id=:id")
                .param("hash", passwords.encode("correct"))
                .param("id", owner.userId())
                .update();
        var login = io.kellermann.tarpeisto.security.PermissionTestSupport.login(http, owner.username(), "correct");
        String read = "/api/v1/assets/" + box + "/packing-contents", write = "/api/v1/assets/" + box + "/seal/apply";
        assertThat(http.getForEntity(read, String.class).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
        var headers = login.headers();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity(write, new org.springframework.http.HttpEntity<>("{}", headers), String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity(
                                write,
                                new org.springframework.http.HttpEntity<>("{\"expectedVersion\":-1}", headers),
                                String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        var noCsrf = login.headers();
        noCsrf.remove("X-XSRF-TOKEN");
        noCsrf.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity(
                                write,
                                new org.springframework.http.HttpEntity<>("{\"expectedVersion\":0}", noCsrf),
                                String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        for (var role : List.of(
                OrganizationRole.OWNER,
                OrganizationRole.DEPUTY,
                OrganizationRole.OPERATOR_AUDITOR,
                OrganizationRole.VIEWER)) {
            jdbc.sql("UPDATE organization_membership SET role=:role WHERE organization_id=:org AND user_id=:user")
                    .param("role", role.name())
                    .param("org", owner.organizationId())
                    .param("user", owner.userId())
                    .update();
            assertThat(http.exchange(
                                    read,
                                    org.springframework.http.HttpMethod.GET,
                                    new org.springframework.http.HttpEntity<>(headers),
                                    String.class)
                            .getStatusCode())
                    .isEqualTo(org.springframework.http.HttpStatus.OK);
            String body = "{\"expectedVersion\":" + placements.get(owner, box).version() + "}";
            assertThat(http.postForEntity(write, new org.springframework.http.HttpEntity<>(body, headers), String.class)
                            .getStatusCode())
                    .isEqualTo(
                            role == OrganizationRole.OWNER || role == OrganizationRole.DEPUTY
                                    ? org.springframework.http.HttpStatus.NO_CONTENT
                                    : org.springframework.http.HttpStatus.FORBIDDEN);
        }
        jdbc.sql("UPDATE organization_membership SET role='OWNER' WHERE organization_id=:org AND user_id=:user")
                .param("org", owner.organizationId())
                .param("user", owner.userId())
                .update();
        var foreign = box(owner());
        assertThat(http.exchange(
                                "/api/v1/assets/" + foreign + "/packing-contents",
                                org.springframework.http.HttpMethod.GET,
                                new org.springframework.http.HttpEntity<>(headers),
                                String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        assertThat(http.postForEntity(
                                "/api/v1/assets/" + foreign + "/seal/apply",
                                new org.springframework.http.HttpEntity<>("{\"expectedVersion\":0}", headers),
                                String.class)
                        .getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    private int appliedCount(UUID box) {
        return jdbc.sql("SELECT count(*) FROM asset_seal_history WHERE asset_id=:id AND action='APPLIED'")
                .param("id", box)
                .query(Integer.class)
                .single();
    }

    private UUID box(TarpeistoPrincipal owner) {
        var model = models.create(
                owner, "Case " + UUID.randomUUID(), null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, true);
        return assets.create(owner, model.id(), "Named case", null, List.of()).id();
    }

    private UUID model(TarpeistoPrincipal owner, String name, TrackingMode tracking) {
        return models.create(
                        owner,
                        name,
                        null,
                        null,
                        null,
                        tracking,
                        tracking == TrackingMode.QUANTITY_STOCK ? "rolls" : null,
                        null,
                        false)
                .id();
    }

    private TarpeistoPrincipal owner() {
        UUID org = UUID.randomUUID(), id = UUID.randomUUID();
        jdbc.sql("INSERT INTO organization(id,name,created_at,updated_at,version) VALUES(:id,:name,now(),now(),0)")
                .param("id", org)
                .param("name", "Reconciliation " + org)
                .update();
        String name = "owner-" + id;
        jdbc.sql(
                        "INSERT INTO app_user(id,username,display_name,password_hash,enabled,created_at,updated_at,version) VALUES(:id,:name,'Owner','test-hash',true,now(),now(),0)")
                .param("id", id)
                .param("name", name)
                .update();
        jdbc.sql(
                        "INSERT INTO organization_membership(id,organization_id,user_id,role,created_at,updated_at,version) VALUES(:id,:org,:user,'OWNER',now(),now(),0)")
                .param("id", UUID.randomUUID())
                .param("org", org)
                .param("user", id)
                .update();
        return new TarpeistoPrincipal(id, name, "Owner", org, OrganizationRole.OWNER);
    }
}

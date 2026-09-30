package io.kellermann.tarpeisto.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.AuditMutationConflictException;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.AuditFindingType;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.PermissionTestSupport;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.AssetModelService;
import io.kellermann.tarpeisto.service.AssetService;
import io.kellermann.tarpeisto.service.AuditService;
import io.kellermann.tarpeisto.service.MediaService;
import io.kellermann.tarpeisto.service.MediaView;
import io.kellermann.tarpeisto.service.OrganizationService;
import io.kellermann.tarpeisto.storage.MediaStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

/** Real PostgreSQL invariants with a controllable S3 boundary for uncertain/racing uploads. */
@Import(AuditEvidenceIntegrationTests.StorageConfiguration.class)
@TestPropertySource(
        properties = {
            "tarpeisto.s3.enabled=true",
            "tarpeisto.s3.region=test",
            "tarpeisto.s3.bucket=test",
            "tarpeisto.s3.access-key=test",
            "tarpeisto.s3.secret-key=test"
        })
class AuditEvidenceIntegrationTests extends AbstractIntegrationTest {
    private static final String PASSWORD = "CorrectHorseBattery1";

    @Autowired
    private MediaService media;

    @Autowired
    private AuditService audits;

    @Autowired
    private AssetModelService models;

    @Autowired
    private AssetService assets;

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private OrganizationMembershipRepository memberships;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private Clock clock;

    @Autowired
    private EvidenceStorage storage;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestRestTemplate rest;

    @AfterEach
    void clearStorageFaults() {
        storage.beforePut = null;
        storage.failPut = false;
    }

    @Test
    void uploadReplayRetainsExactFindingAndBytesEvenAfterCompletion() throws IOException {
        Fixture f = fixture();
        UUID operation = UUID.randomUUID();
        MediaView first = upload(f, operation, png(0x112233));
        assertThat(upload(f, operation, png(0x112233)).id()).isEqualTo(first.id());
        assertThat(first.auditId()).isEqualTo(f.audit());
        assertThat(first.findingId()).isEqualTo(f.finding());
        assertThat(media.listFindingEvidence(f.actor(), f.finding())).hasSize(1);
        audits.complete(f.actor(), f.audit(), UUID.randomUUID(), f.code(), true, false);
        assertThat(upload(f, operation, png(0x112233)).id()).isEqualTo(first.id());
        assertThat(media.open(f.actor(), first.id(), false).inputStream().readAllBytes())
                .isEqualTo(png(0x112233));
        assertThat(media.open(f.actor(), first.id(), true).inputStream().readAllBytes())
                .isNotEmpty();
        assertThatThrownBy(() -> upload(f, UUID.randomUUID(), png(0x112233)))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> media.delete(f.actor(), first.id())).isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> media.retryCleanup(f.actor(), first.id()))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void changedContentOrFindingCannotReuseAnUploadOperation() {
        Fixture f = fixture();
        UUID operation = UUID.randomUUID();
        upload(f, operation, png(0x112233));
        assertThatThrownBy(() -> upload(f, operation, png(0x445566)))
                .isInstanceOf(AuditMutationConflictException.class);
        var another = audits.recordFinding(
                f.actor(), f.audit(), UUID.randomUUID(), AuditFindingType.DAMAGED, null, "Other label");
        UUID otherFinding = another.findings().stream()
                .filter(x -> "Other label".equals(x.note()))
                .findFirst()
                .orElseThrow()
                .id();
        assertThatThrownBy(() ->
                        media.uploadAuditEvidence(f.actor(), f.audit(), otherFinding, operation, file(png(0x112233))))
                .isInstanceOf(AuditMutationConflictException.class);
    }

    @Test
    void authorizationAndTenantBoundariesApplyToEvidenceAndSourceOperations() {
        Fixture f = fixture();
        TarpeistoPrincipal operator = new TarpeistoPrincipal(
                f.actor().userId(),
                f.actor().username(),
                "Operator",
                f.actor().organizationId(),
                OrganizationRole.OPERATOR_AUDITOR);
        MediaView uploaded =
                media.uploadAuditEvidence(operator, f.audit(), f.finding(), UUID.randomUUID(), file(png(1)));
        TarpeistoPrincipal viewer = new TarpeistoPrincipal(
                f.actor().userId(),
                f.actor().username(),
                "Viewer",
                f.actor().organizationId(),
                OrganizationRole.VIEWER);
        assertThatThrownBy(() ->
                        media.uploadAuditEvidence(viewer, f.audit(), f.finding(), UUID.randomUUID(), file(png(1))))
                .isInstanceOf(AccessDeniedException.class);
        Fixture other = fixture();
        assertThatThrownBy(() -> media.listAuditEvidence(other.actor(), f.audit()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> media.open(other.actor(), uploaded.id(), false))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> media.uploadAuditEvidence(
                        other.actor(), f.audit(), f.finding(), UUID.randomUUID(), file(png(1))))
                .isInstanceOf(NotFoundException.class);
        assertThat(audits.get(f.actor(), f.task()).findings())
                .anyMatch(x -> f.findingOperation().equals(x.sourceOperationId()));
        UUID scanOperation = UUID.randomUUID();
        var equipmentModel = models.create(
                f.actor(), "Equipment", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, false);
        var equipment = assets.create(f.actor(), equipmentModel.id(), null, null, List.of());
        assertThat(audits.scan(f.actor(), f.audit(), scanOperation, equipment.publicCode())
                        .scans())
                .anyMatch(x -> scanOperation.equals(x.operationId()));
    }

    @Test
    void rejectsInvalidImagesAndCleansUpBothObjectsOnStorageFailure() {
        Fixture f = fixture();
        assertThatThrownBy(() -> upload(f, UUID.randomUUID(), "not an image".getBytes()))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> upload(f, UUID.randomUUID(), new byte[10 * 1024 * 1024 + 1]))
                .isInstanceOf(ValidationFailedException.class);
        int objectsBefore = storage.objects.size();
        storage.failPut = true;
        assertThatThrownBy(() -> upload(f, UUID.randomUUID(), png(1))).isInstanceOf(IllegalStateException.class);
        assertThat(storage.objects).hasSize(objectsBefore);
        assertThat(media.listAuditEvidence(f.actor(), f.audit())).isEmpty();
    }

    @Test
    void concurrentDuplicateUploadsKeepOnlyWinningObjects() throws Exception {
        Fixture f = fixture();
        UUID operation = UUID.randomUUID();
        int objectsBefore = storage.objects.size();
        CountDownLatch originals = new CountDownLatch(2);
        storage.beforePut = key -> {
            if (key.contains("original")) {
                originals.countDown();
                try {
                    assertThat(originals.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> upload(f, operation, png(1)));
            var second = executor.submit(() -> upload(f, operation, png(1)));
            assertThat(first.get(20, TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(20, TimeUnit.SECONDS).id());
        }
        assertThat(media.listAuditEvidence(f.actor(), f.audit())).hasSize(1);
        assertThat(storage.objects).hasSize(objectsBefore + 2);
    }

    @Test
    void completionDuringStorageUploadRejectsEvidenceAndRemovesStagingObjects() {
        Fixture f = fixture();
        int objectsBefore = storage.objects.size();
        storage.beforePut = key -> {
            if (key.contains("original"))
                audits.complete(f.actor(), f.audit(), UUID.randomUUID(), f.code(), true, false);
        };
        assertThatThrownBy(() -> upload(f, UUID.randomUUID(), png(1))).isInstanceOf(ValidationFailedException.class);
        assertThat(media.listAuditEvidence(f.actor(), f.audit())).isEmpty();
        assertThat(storage.objects).hasSize(objectsBefore);
    }

    @Test
    void databaseBlocksEvidenceMutationAndWrongAuditAssociation() {
        Fixture f = fixture();
        MediaView uploaded = upload(f, UUID.randomUUID(), png(1));
        assertThatThrownBy(() -> jdbc.sql("UPDATE media_object SET sha256 = repeat('b', 64) WHERE id = :id")
                        .param("id", uploaded.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM media_object WHERE id = :id")
                        .param("id", uploaded.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        var secondAsset = assets.create(
                f.actor(),
                models.create(
                                f.actor(),
                                "Second " + UUID.randomUUID(),
                                null,
                                null,
                                null,
                                TrackingMode.SERIALIZED_ASSET,
                                null,
                                null,
                                true)
                        .id(),
                "Second case",
                null,
                List.of());
        var secondAudit =
                audits.launchContainerAudit(f.actor(), secondAsset.id(), secondAsset.publicCode(), UUID.randomUUID());
        assertThatThrownBy(() -> media.uploadAuditEvidence(
                        f.actor(), secondAudit.id(), f.finding(), UUID.randomUUID(), file(png(1))))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> insertEvidence(f, secondAudit.id())).isInstanceOf(SQLException.class);
    }

    @Test
    void evidenceInsertionWaitsForDirectDatabaseCompletionAndThenRejects() throws Exception {
        Fixture f = fixture();
        try (Connection completion = dataSource.getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            completion.setAutoCommit(false);
            try (var statement = completion.prepareStatement(
                    "UPDATE container_audit SET state = 'COMPLETED', completed_at = now(), completion_outcome = 'FINDINGS' WHERE id = ?")) {
                statement.setObject(1, f.audit());
                statement.executeUpdate();
            }
            CountDownLatch attempting = new CountDownLatch(1);
            var insertion = executor.submit(() -> {
                attempting.countDown();
                try {
                    insertEvidence(f, f.audit());
                    return "accepted";
                } catch (SQLException failure) {
                    return failure.getSQLState();
                }
            });
            try {
                assertThat(attempting.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> insertion.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                completion.commit();
            }
            assertThat(insertion.get(10, TimeUnit.SECONDS)).isEqualTo("23514");
        }
        assertThat(media.listAuditEvidence(f.actor(), f.audit())).isEmpty();
    }

    @Test
    void actorPreconditionRejectsCookieSwitchWithoutApplyingQueuedScan() {
        Fixture f = fixture();
        var session = PermissionTestSupport.login(rest, f.actor().username(), PASSWORD);
        var headers = session.headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tarpeisto-Audit-Actor", f.actor().organizationId() + ":" + UUID.randomUUID());
        var response = rest.exchange(
                "/api/v1/audits/" + f.audit() + "/scans",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", f.code(), "operationId", UUID.randomUUID()), headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("AUDIT_ACTOR_CHANGED");
        assertThat(audits.get(f.actor(), f.task()).scans()).isEmpty();
    }

    private void insertEvidence(Fixture f, UUID audit) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                INSERT INTO media_object(id, organization_id, purpose, object_key, thumbnail_object_key,
                    content_type, byte_size, sha256, uploader_user_id, created_at, updated_at,
                    container_audit_id, audit_finding_id, upload_operation_id)
                VALUES (?, ?, 'AUDIT_EVIDENCE', ?, ?, 'image/png', 1, repeat('a',64), ?, now(), now(), ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, f.actor().organizationId());
            statement.setString(3, "test/" + UUID.randomUUID());
            statement.setString(4, "test/" + UUID.randomUUID());
            statement.setObject(5, f.actor().userId());
            statement.setObject(6, audit);
            statement.setObject(7, f.finding());
            statement.setObject(8, UUID.randomUUID());
            statement.executeUpdate();
        }
    }

    private Fixture fixture() {
        UUID org = organizations
                .ensureOrganizationExists("Evidence " + UUID.randomUUID())
                .getId();
        UUID userId = UUID.randomUUID();
        String name = "evidence-" + userId;
        users.save(
                new User(userId, name, null, "Evidence operator", passwords.encode(PASSWORD), true, clock.instant()));
        memberships.save(
                new OrganizationMembership(UUID.randomUUID(), org, userId, OrganizationRole.OWNER, clock.instant()));
        var actor = new TarpeistoPrincipal(userId, name, "Evidence operator", org, OrganizationRole.OWNER);
        var model = models.create(actor, "Case", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, true);
        var asset = assets.create(actor, model.id(), "Evidence case", null, List.of());
        var audit = audits.launchContainerAudit(actor, asset.id(), asset.publicCode(), UUID.randomUUID());
        UUID operation = UUID.randomUUID();
        var finding = audits.recordFinding(
                        actor, audit.id(), operation, AuditFindingType.DAMAGED, null, "Broken handle")
                .findings()
                .getFirst();
        return new Fixture(actor, audit.id(), audit.taskId(), asset.publicCode(), finding.id(), operation);
    }

    private MediaView upload(Fixture f, UUID operation, byte[] bytes) {
        return media.uploadAuditEvidence(f.actor(), f.audit(), f.finding(), operation, file(bytes));
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "photo.png", "image/png", bytes);
    }

    private static byte[] png(int rgb) {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, rgb);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Fixture(
            TarpeistoPrincipal actor, UUID audit, UUID task, String code, UUID finding, UUID findingOperation) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
        @Bean
        @Primary
        EvidenceStorage evidenceStorage() {
            return new EvidenceStorage();
        }
    }

    static class EvidenceStorage implements MediaStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private volatile java.util.function.Consumer<String> beforePut;
        private volatile boolean failPut;

        public void put(String key, String type, long length, InputStream input) {
            if (beforePut != null) beforePut.accept(key);
            try {
                objects.put(key, input.readAllBytes());
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            if (failPut) throw new IllegalStateException("Injected storage failure");
        }

        public StoredMedia get(String key) {
            byte[] bytes = objects.get(key);
            return new StoredMedia(new ByteArrayInputStream(bytes), "image/png", bytes.length);
        }

        public void delete(String key) {
            objects.remove(key);
        }
    }
}

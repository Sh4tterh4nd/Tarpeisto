package io.kellermann.tarpeisto.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.AuditMutationConflictException;
import io.kellermann.tarpeisto.exception.InvalidCredentialsException;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.AuditFindingType;
import io.kellermann.tarpeisto.model.BookingLineType;
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
import io.kellermann.tarpeisto.service.BookingReservationService;
import io.kellermann.tarpeisto.service.BookingService;
import io.kellermann.tarpeisto.service.CheckoutService;
import io.kellermann.tarpeisto.service.MediaService;
import io.kellermann.tarpeisto.service.OrganizationService;
import io.kellermann.tarpeisto.service.TemporaryAccessService;
import io.kellermann.tarpeisto.storage.MediaStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

/** Real database and browser-cookie boundaries, including fixed time and service-level denial. */
@Import(TemporaryVolunteerAccessIntegrationTests.TimeConfiguration.class)
@TestPropertySource(
        properties = {
            "tarpeisto.s3.enabled=true",
            "tarpeisto.s3.region=test",
            "tarpeisto.s3.bucket=test",
            "tarpeisto.s3.access-key=test",
            "tarpeisto.s3.secret-key=test"
        })
class TemporaryVolunteerAccessIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private TemporaryAccessService access;

    @Autowired
    private AuditService audits;

    @Autowired
    private AssetService assets;

    @Autowired
    private AssetModelService models;

    @Autowired
    private BookingService bookings;

    @Autowired
    private BookingReservationService reservations;

    @Autowired
    private CheckoutService checkout;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private OrganizationMembershipRepository memberships;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MediaService media;

    @Autowired
    private EvidenceStorage storage;

    @AfterEach
    void resetTime() {
        clock.reset();
        storage.beforePut = null;
    }

    @Test
    void sharedInvitationCreatesDistinctActorsAndRetryPreservesOriginalIdentity() {
        Fixture f = fixture();
        var issued = access.create(f.owner(), null, f.batch());
        assertThat(issued.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(issued.invitation().expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
        assertThat(jdbc.sql("SELECT token_hash FROM temporary_access_invitation WHERE id = :id")
                        .param("id", issued.invitation().id())
                        .query(String.class)
                        .single())
                .isEqualTo(TemporaryAccessService.digest(issued.token()))
                .doesNotContain(issued.token());
        UUID operation = UUID.randomUUID();
        var first = access.redeem(issued.token(), "  Volunteer   One  ", operation);
        var replay = access.redeem(issued.token(), "Volunteer One", operation);
        var second = access.redeem(issued.token(), "Volunteer One", UUID.randomUUID());
        assertThat(replay).isEqualTo(first);
        assertThat(second.userId()).isNotEqualTo(first.userId());
        assertThat(first.displayName()).isEqualTo("Volunteer One");
        assertThat(first.temporaryAccess().expiresAt())
                .isEqualTo(issued.invitation().expiresAt());
        assertThatThrownBy(() -> access.redeem(issued.token(), "Different person", operation))
                .isInstanceOf(AuditMutationConflictException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM organization_membership WHERE user_id = :id")
                        .param("id", first.userId())
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(users.findById(first.userId()).orElseThrow().getPasswordHash())
                .isNull();
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_user SET password_hash = 'fake' WHERE id = :id")
                        .param("id", first.userId())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> memberships.saveAndFlush(new OrganizationMembership(
                        UUID.randomUUID(),
                        first.organizationId(),
                        first.userId(),
                        OrganizationRole.OWNER,
                        clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class);
        var observed =
                audits.recordFinding(first, f.audit(), UUID.randomUUID(), AuditFindingType.DAMAGED, null, "Handle");
        UUID finding = observed.findings().getFirst().id();
        assertThat(observed.findings().getFirst().recordedByUserId()).isEqualTo(first.userId());
        assertThat(observed.findings().getFirst().recordedByDisplayName()).isEqualTo("Volunteer One");
        assertThat(ContainerAuditResponse.from(observed).findings().getFirst().recordedByDisplayName())
                .isEqualTo("Volunteer One");
        var item = assets.create(f.owner(), f.model(), "Observed item", null, List.of());
        jdbc.sql("UPDATE physical_asset SET parent_container_asset_id=:parent WHERE id=:id AND organization_id=:org")
                .param("parent", f.container())
                .param("id", item.id())
                .param("org", f.owner().organizationId())
                .update();
        var scanned = audits.scan(second, f.audit(), UUID.randomUUID(), item.publicCode());
        assertThat(scanned.scans().getFirst().recordedByUserId()).isEqualTo(second.userId());
        assertThat(scanned.scans().getFirst().recordedByDisplayName()).isEqualTo("Volunteer One");
        assertThat(ContainerAuditResponse.from(scanned).scans().getFirst().recordedByUserId())
                .isEqualTo(second.userId());
        assertThat(jdbc.sql("SELECT recorded_by_user_id FROM audit_finding WHERE id = :id")
                        .param("id", finding)
                        .query(UUID.class)
                        .single())
                .isEqualTo(first.userId());
    }

    @Test
    void creationQrDecodesToFragmentLinkAndListingNeverReturnsItsCredential() throws Exception {
        Fixture f = fixture();
        var owner = PermissionTestSupport.login(rest, f.owner().username(), "CorrectHorseBattery1");
        var headers = owner.headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.exchange(
                "/api/v1/temporary-access/invitations",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("auditBatchId", f.batch(), "joinUrl", "https://tarpeisto.example/join"), headers),
                TemporaryInvitationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var issued = response.getBody();
        assertThat(issued).isNotNull();
        assertThat(issued.joinUrl()).isEqualTo("https://tarpeisto.example/join#token=" + issued.token());
        byte[] png = Base64.getDecoder().decode(issued.qrCodeDataUrl().substring("data:image/png;base64,".length()));
        var pixels = ImageIO.read(new ByteArrayInputStream(png));
        var qr = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(pixels)));
        assertThat(new MultiFormatReader().decode(qr).getText()).isEqualTo(issued.joinUrl());
        var listing = rest.exchange(
                "/api/v1/temporary-access/invitations?auditBatchId=" + f.batch(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listing.getBody())
                .contains(issued.invitation().id().toString())
                .doesNotContain(issued.token(), "tokenHash", "qrCodeDataUrl", "joinUrl");
    }

    @Test
    void fixedDeadlineRejectsExistingSessionAndRedemptionAtExactBoundary() {
        Fixture f = fixture();
        var issued = access.create(f.owner(), null, f.batch());
        clock.set(issued.invitation().expiresAt().minus(Duration.ofHours(1)));
        var volunteer = access.redeem(issued.token(), "Late arrival", UUID.randomUUID());
        var browser = new BrowserSession();
        assertThat(browser.post(
                                "/api/v1/temporary-access/redemptions",
                                Map.of(
                                        "token",
                                        issued.token(),
                                        "displayName",
                                        "Phone volunteer",
                                        "operationId",
                                        UUID.randomUUID()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        clock.set(issued.invitation().expiresAt().minusMillis(1));
        assertThat(access.refresh(volunteer).temporaryAccess().expiresAt())
                .isEqualTo(issued.invitation().expiresAt());
        assertThat(browser.get("/api/v1/session").getStatusCode()).isEqualTo(HttpStatus.OK);
        clock.set(issued.invitation().expiresAt());
        assertThatThrownBy(() -> access.refresh(volunteer)).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> access.redeem(issued.token(), "Another", UUID.randomUUID()))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> audits.recordFinding(
                        volunteer, f.audit(), UUID.randomUUID(), AuditFindingType.DAMAGED, null, "Late"))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(browser.get("/api/v1/session").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void revokeStopsAllDerivedActorsAndBrowserSessionImmediately() {
        Fixture f = fixture();
        var issued = access.create(f.owner(), null, f.batch());
        var one = access.redeem(issued.token(), "One", UUID.randomUUID());
        var two = access.redeem(issued.token(), "Two", UUID.randomUUID());
        var browser = new BrowserSession();
        browser.post(
                "/api/v1/temporary-access/redemptions",
                Map.of("token", issued.token(), "displayName", "Browser", "operationId", UUID.randomUUID()));
        access.revoke(f.owner(), issued.invitation().id());
        assertThatThrownBy(() -> audits.get(one, f.task())).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() ->
                        audits.recordFinding(two, f.audit(), UUID.randomUUID(), AuditFindingType.DAMAGED, null, "Late"))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> access.redeem(issued.token(), "Three", UUID.randomUUID()))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(browser.get("/api/v1/temporary-access/tasks").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(access.list(f.owner(), null, f.batch()).getFirst().revokedAt())
                .isNotNull();
    }

    @Test
    void unrelatedValidAssetCodesAreUnknownAndCannotBeUsedForFindings() {
        Fixture f = fixture();
        var issued = access.create(f.owner(), null, f.batch());
        var volunteer = access.redeem(issued.token(), "Scanner", UUID.randomUUID());
        var outside = assets.create(f.owner(), f.model(), "Private equipment", null, List.of());
        var observation = audits.scan(volunteer, f.audit(), UUID.randomUUID(), outside.publicCode());
        assertThat(observation.scans()).isEmpty();
        assertThat(observation.findings()).anySatisfy(finding -> {
            assertThat(finding.type()).isEqualTo("UNKNOWN_CODE");
            assertThat(finding.assetId()).isNull();
            assertThat(finding.detail())
                    .contains(outside.publicCode())
                    .doesNotContain(outside.id().toString(), "Private equipment");
        });
        assertThatThrownBy(() -> audits.recordFinding(
                        volunteer, f.audit(), UUID.randomUUID(), AuditFindingType.DAMAGED, outside.id(), "Private"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> audits.launchContainerAudit(volunteer, f.container(), f.code(), UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> assets.get(volunteer, f.container())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> models.get(volunteer, f.model())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void managersAreTenantBoundedAndPermanentOperatorsCannotIssueInvitations() {
        Fixture f = fixture();
        Fixture other = fixture();
        var operator = new TarpeistoPrincipal(
                f.owner().userId(),
                f.owner().username(),
                "Operator",
                f.owner().organizationId(),
                OrganizationRole.OPERATOR_AUDITOR);
        var deputy = new TarpeistoPrincipal(
                f.owner().userId(),
                f.owner().username(),
                "Deputy",
                f.owner().organizationId(),
                OrganizationRole.DEPUTY);
        assertThatThrownBy(() -> access.create(operator, null, f.batch())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.create(f.owner(), null, other.batch())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> access.create(f.owner(), null, null)).isInstanceOf(ValidationFailedException.class);
        assertThat(access.create(deputy, null, f.batch()).invitation().organizationId())
                .isEqualTo(f.owner().organizationId());
        var issued = access.create(f.owner(), null, f.batch());
        assertThatThrownBy(
                        () -> access.revoke(other.owner(), issued.invitation().id()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void temporaryHttpIdentityCannotReachInventoryOrAdministrationFamilies() {
        Fixture f = fixture();
        var issued = access.create(f.owner(), null, f.batch());
        var browser = new BrowserSession();
        assertThat(browser.post(
                                "/api/v1/temporary-access/redemptions",
                                Map.of(
                                        "token",
                                        issued.token(),
                                        "displayName",
                                        "Phone",
                                        "operationId",
                                        UUID.randomUUID()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(browser.get("/api/v1/audits/tasks/" + f.task()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(browser.get("/api/v1/audits/tasks/" + f.task() + "/container")
                        .getBody())
                .contains(f.code());
        for (String path : List.of(
                "/api/v1/assets",
                "/api/v1/assets/" + f.container(),
                "/api/v1/asset-models",
                "/api/v1/users",
                "/api/v1/categories",
                "/api/v1/locations",
                "/api/v1/bookings",
                "/api/v1/review/findings",
                "/api/v1/temporary-access/invitations?auditBatchId=" + f.batch())) {
            assertThat(browser.get(path).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        var other = fixture();
        assertThat(browser.get("/api/v1/audits/tasks/" + other.task()).getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    @Test
    void redemptionRequiresCsrfAndUnavailableInvitationsHaveOnePublicResponse() {
        var noCsrf = rest.postForEntity(
                "/api/v1/temporary-access/redemptions",
                Map.of("token", "a".repeat(43), "displayName", "Volunteer", "operationId", UUID.randomUUID()),
                String.class);
        assertThat(noCsrf.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Fixture f = fixture();
        var issued = access.create(f.owner(), null, f.batch());
        access.revoke(f.owner(), issued.invitation().id());
        var browser = new BrowserSession();
        var revoked = browser.post(
                "/api/v1/temporary-access/redemptions",
                Map.of("token", issued.token(), "displayName", "Volunteer", "operationId", UUID.randomUUID()));
        var unknown = browser.post(
                "/api/v1/temporary-access/redemptions",
                Map.of("token", "a".repeat(43), "displayName", "Volunteer", "operationId", UUID.randomUUID()));
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getStatusCode()).isEqualTo(revoked.getStatusCode());
        assertThat(unknown.getBody()).isEqualTo(revoked.getBody());
    }

    @Test
    void mediaListingsAndBothStreamingVariantsEnforceTheAssignedScope() throws IOException {
        Fixture f = fixture();
        var issued = access.create(f.owner(), null, f.batch());
        var volunteer = access.redeem(issued.token(), "Photographer", UUID.randomUUID());
        var allowed = media.uploadAssetReference(f.owner(), f.container(), image());
        var outside = assets.create(f.owner(), f.model(), "Unassigned case", null, List.of());
        var forbidden = media.uploadAssetReference(f.owner(), outside.id(), image());
        assertThat(media.getAssetReference(volunteer, f.container()).id()).isEqualTo(allowed.id());
        assertThat(media.open(volunteer, allowed.id(), false).inputStream().readAllBytes())
                .isNotEmpty();
        assertThat(media.open(volunteer, allowed.id(), true).inputStream().readAllBytes())
                .isNotEmpty();
        assertThatThrownBy(() -> media.getAssetReference(volunteer, outside.id()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> media.open(volunteer, forbidden.id(), false))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> media.open(volunteer, forbidden.id(), true)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> media.uploadAssetReference(volunteer, f.container(), image()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void revocationDuringEvidenceTransferRejectsFinalizationAndCleansAttemptObjects() {
        Fixture f = fixture();
        var issued = access.create(f.owner(), null, f.batch());
        var volunteer = access.redeem(issued.token(), "Photographer", UUID.randomUUID());
        UUID finding = audits.recordFinding(
                        volunteer, f.audit(), UUID.randomUUID(), AuditFindingType.DAMAGED, null, "Handle")
                .findings()
                .getFirst()
                .id();
        int before = storage.objects.size();
        storage.beforePut = key -> {
            if (key.contains("original"))
                access.revoke(f.owner(), issued.invitation().id());
        };
        assertThatThrownBy(() -> media.uploadAuditEvidence(volunteer, f.audit(), finding, UUID.randomUUID(), image()))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(media.listAuditEvidence(f.owner(), f.audit())).isEmpty();
        assertThat(storage.objects).hasSize(before);
    }

    @Test
    void expiryDuringEvidenceTransferRejectsFinalizationAndCleansAttemptObjects() {
        Fixture f = fixture();
        var issued = access.create(f.owner(), null, f.batch());
        var volunteer = access.redeem(issued.token(), "Photographer", UUID.randomUUID());
        UUID finding = audits.recordFinding(
                        volunteer, f.audit(), UUID.randomUUID(), AuditFindingType.DAMAGED, null, "Handle")
                .findings()
                .getFirst()
                .id();
        int before = storage.objects.size();
        storage.beforePut = key -> {
            if (key.contains("original")) clock.set(issued.invitation().expiresAt());
        };
        assertThatThrownBy(() -> media.uploadAuditEvidence(volunteer, f.audit(), finding, UUID.randomUUID(), image()))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(media.listAuditEvidence(f.owner(), f.audit())).isEmpty();
        assertThat(storage.objects).hasSize(before);
    }

    @Test
    void eventInvitationIssuedBeforeReturnDiscoversNewTasksAndCanCompleteThem() {
        Fixture f = fixture();
        audits.complete(f.owner(), f.audit(), UUID.randomUUID(), f.code(), true, false);
        var event = bookings.create(
                f.owner(),
                UUID.randomUUID(),
                "Invited event",
                null,
                null,
                null,
                clock.instant(),
                clock.instant().plusSeconds(3600));
        event = bookings.addLine(
                f.owner(), event.id(), event.version(), BookingLineType.CONTAINER, f.container(), null, BigDecimal.ONE);
        var issued = access.create(f.owner(), event.id(), null);
        var volunteer = access.redeem(issued.token(), "Return helper", UUID.randomUUID());
        assertThat(audits.assignedTasks(volunteer)).isEmpty();
        reservations.reserve(f.owner(), event.id(), event.version());
        event = bookings.get(f.owner(), event.id());
        checkout.checkout(f.owner(), event.id(), event.version(), UUID.randomUUID(), null, List.of());
        var returned = checkout.checkInAsset(f.owner(), event.id(), f.container(), UUID.randomUUID());
        assertThat(audits.assignedTasks(volunteer)).hasSize(1);
        UUID task = returned.auditTasks().getFirst().id();
        var started = audits.start(volunteer, task, f.code());
        assertThat(audits.complete(volunteer, started.id(), UUID.randomUUID(), f.code(), true, false)
                        .state())
                .isEqualTo("COMPLETED");
        UUID eventId = event.id();
        assertThatThrownBy(() -> bookings.get(volunteer, eventId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void revocationCommittedWhileMutationWaitsForOrganizationLockPreventsTheWrite() throws Exception {
        Fixture f = fixture();
        var issued = access.create(f.owner(), null, f.batch());
        var volunteer = access.redeem(issued.token(), "Queued helper", UUID.randomUUID());
        try (Connection connection = dataSource.getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            lockOrganization(connection, f.owner().organizationId());
            CountDownLatch attempted = new CountDownLatch(1);
            var write = executor.submit(() -> {
                attempted.countDown();
                return audits.recordFinding(
                        volunteer, f.audit(), UUID.randomUUID(), AuditFindingType.DAMAGED, null, "Race");
            });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> write.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                revoke(connection, issued.invitation().id(), f.owner().userId());
            } finally {
                connection.commit();
            }
            assertThatThrownBy(() -> write.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(audits.get(f.owner(), f.task()).findings()).isEmpty();
    }

    @Test
    void revocationCommittedWhileRedemptionWaitsForOrganizationLockPreventsActorCreation() throws Exception {
        Fixture f = fixture();
        var issued = access.create(f.owner(), null, f.batch());
        try (Connection connection = dataSource.getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            lockOrganization(connection, f.owner().organizationId());
            CountDownLatch attempted = new CountDownLatch(1);
            var join = executor.submit(() -> {
                attempted.countDown();
                return access.redeem(issued.token(), "Race helper", UUID.randomUUID());
            });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> join.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                revoke(connection, issued.invitation().id(), f.owner().userId());
            } finally {
                connection.commit();
            }
            assertThatThrownBy(() -> join.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM volunteer_session WHERE invitation_id = :id")
                        .param("id", issued.invitation().id())
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    private void lockOrganization(Connection connection, UUID organization) throws Exception {
        try (var statement = connection.prepareStatement("SELECT id FROM organization WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, organization);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    private void revoke(Connection connection, UUID invitation, UUID actor) throws Exception {
        try (var statement = connection.prepareStatement(
                "UPDATE temporary_access_invitation SET revoked_at = ?, revoked_by_user_id = ? WHERE id = ?")) {
            statement.setObject(1, java.sql.Timestamp.from(clock.instant()));
            statement.setObject(2, actor);
            statement.setObject(3, invitation);
            statement.executeUpdate();
        }
    }

    private static MockMultipartFile image() {
        try {
            var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            pixels.setRGB(0, 0, 0x112233);
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(pixels, "png", bytes);
            return new MockMultipartFile("file", "evidence.png", "image/png", bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Fixture fixture() {
        UUID org = organizations
                .ensureOrganizationExists("Volunteer " + UUID.randomUUID())
                .getId();
        UUID user = UUID.randomUUID();
        String username = "volunteer-owner-" + user;
        users.save(new User(
                user, username, null, "Owner", passwords.encode("CorrectHorseBattery1"), true, clock.instant()));
        memberships.save(
                new OrganizationMembership(UUID.randomUUID(), org, user, OrganizationRole.OWNER, clock.instant()));
        var owner = new TarpeistoPrincipal(user, username, "Owner", org, OrganizationRole.OWNER);
        var model =
                models.create(owner, "Audit case", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, true);
        var container = assets.create(owner, model.id(), "Assigned case", null, List.of());
        var audit = audits.launchContainerAudit(owner, container.id(), container.publicCode(), UUID.randomUUID());
        return new Fixture(
                owner, model.id(), container.id(), container.publicCode(), audit.batchId(), audit.taskId(), audit.id());
    }

    private record Fixture(
            TarpeistoPrincipal owner, UUID model, UUID container, String code, UUID batch, UUID task, UUID audit) {}

    private final class BrowserSession {
        private final Map<String, String> cookies = new LinkedHashMap<>();

        BrowserSession() {
            absorb(rest.getForEntity("/api/v1/application", String.class));
        }

        ResponseEntity<String> get(String path) {
            var response = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers()), String.class);
            absorb(response);
            return response;
        }

        ResponseEntity<String> post(String path, Object body) {
            var response = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers()), String.class);
            absorb(response);
            return response;
        }

        private HttpHeaders headers() {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(
                    HttpHeaders.COOKIE,
                    cookies.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(java.util.stream.Collectors.joining("; ")));
            if (cookies.containsKey("XSRF-TOKEN")) headers.set("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            return headers;
        }

        private void absorb(ResponseEntity<?> response) {
            for (String cookie : response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
                String pair = cookie.split(";", 2)[0];
                int separator = pair.indexOf('=');
                cookies.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock volunteerClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        EvidenceStorage volunteerStorage() {
            return new EvidenceStorage();
        }
    }

    static final class EvidenceStorage implements MediaStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private volatile java.util.function.Consumer<String> beforePut;

        @Override
        public void put(String key, String type, long length, InputStream input) {
            if (beforePut != null) beforePut.accept(key);
            try {
                objects.put(key, input.readAllBytes());
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public StoredMedia get(String key) {
            byte[] bytes = objects.get(key);
            return new StoredMedia(new ByteArrayInputStream(bytes), "image/png", bytes.length);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }
    }

    static final class MutableClock extends Clock {
        private final Instant initial = Instant.now().truncatedTo(ChronoUnit.MICROS);
        private volatile Instant now = initial;

        void set(Instant value) {
            now = value;
        }

        void reset() {
            now = initial;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

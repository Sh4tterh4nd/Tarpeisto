package io.kellermann.bigcontainers.controller;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.kellermann.bigcontainers.storage.MediaStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL-backed authorization and persistence coverage for the media HTTP boundary. */
@Import(MediaControllerIntegrationTests.MediaStorageTestConfiguration.class)
@TestPropertySource(
        properties = {
            "bigcontainers.s3.enabled=true",
            "bigcontainers.s3.region=test-region",
            "bigcontainers.s3.bucket=test-bucket",
            "bigcontainers.s3.access-key=test-access",
            "bigcontainers.s3.secret-key=test-secret"
        })
class MediaControllerIntegrationTests extends AbstractIntegrationTest {
    private static final String PASSWORD = "CorrectHorseBattery1";
    private static final byte[] PNG = createPng();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @Autowired
    private InMemoryMediaStorage mediaStorage;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void ownerCanUploadReplaceAndStreamAModelReferencePhoto() {
        Fixture fixture = fixture();
        UUID modelId = model(fixture, false);
        String first = upload(fixture.owner(), "/api/v1/asset-models/" + modelId + "/media/reference", PNG)
                .getBody();
        String mediaId = field(first, "id");

        assertThat(restTemplate
                        .exchange(
                                "/api/v1/asset-models/" + modelId + "/media/reference",
                                HttpMethod.GET,
                                new HttpEntity<>(fixture.owner().headers()),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        var stream = restTemplate.exchange(
                "/api/v1/media/" + mediaId,
                HttpMethod.GET,
                new HttpEntity<>(fixture.owner().headers()),
                byte[].class);
        assertThat(stream.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stream.getBody()).isEqualTo(PNG);

        var replacement = upload(fixture.owner(), "/api/v1/asset-models/" + modelId + "/media/reference", PNG);
        assertThat(replacement.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(field(replacement.getBody(), "id")).isNotEqualTo(mediaId);
        assertThat(restTemplate
                        .exchange(
                                "/api/v1/media/" + mediaId,
                                HttpMethod.GET,
                                new HttpEntity<>(fixture.owner().headers()),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        UUID assetId = asset(fixture, modelId);
        String assetPhoto = field(
                upload(fixture.owner(), "/api/v1/assets/" + assetId + "/media/reference", PNG)
                        .getBody(),
                "id");
        assertThat(upload(fixture.owner(), "/api/v1/assets/" + assetId + "/media/reference", PNG)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(restTemplate
                        .exchange(
                                "/api/v1/media/" + assetPhoto,
                                HttpMethod.GET,
                                new HttpEntity<>(fixture.owner().headers()),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewerAndOperatorCannotUploadAndOtherOrganizationCannotSeePhoto() {
        Fixture fixture = fixture();
        UUID modelId = model(fixture, false);
        String mediaId = field(
                upload(fixture.owner(), "/api/v1/asset-models/" + modelId + "/media/reference", PNG)
                        .getBody(),
                "id");
        assertThat(upload(
                                fixture.session(OrganizationRole.VIEWER),
                                "/api/v1/asset-models/" + modelId + "/media/reference",
                                PNG)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(
                                fixture.session(OrganizationRole.OPERATOR_AUDITOR),
                                "/api/v1/asset-models/" + modelId + "/media/reference",
                                PNG)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        Fixture other = fixture();
        assertThat(restTemplate
                        .exchange(
                                "/api/v1/media/" + mediaId,
                                HttpMethod.GET,
                                new HttpEntity<>(other.owner().headers()),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsInvalidAndOversizedImagePayloads() {
        Fixture fixture = fixture();
        UUID modelId = model(fixture, false);
        assertThat(upload(
                                fixture.owner(),
                                "/api/v1/asset-models/" + modelId + "/media/reference",
                                "not-image".getBytes())
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        assertThat(upload(fixture.owner(), "/api/v1/asset-models/" + modelId + "/media/reference", oversized)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void layoutOrderPrimaryAndCleanupRetryArePersisted() {
        Fixture fixture = fixture();
        UUID container = asset(fixture, model(fixture, true));
        String first = upload(fixture.owner(), "/api/v1/assets/" + container + "/media/layout?caption=Bottom", PNG)
                .getBody();
        String second = upload(fixture.owner(), "/api/v1/assets/" + container + "/media/layout?caption=Top", PNG)
                .getBody();
        String firstId = field(first, "id");
        String secondId = field(second, "id");
        long firstVersion = Long.parseLong(field(first, "version"));
        var reordered = json(
                fixture.owner(),
                HttpMethod.PUT,
                "/api/v1/media/" + firstId + "/layout",
                Map.of("caption", "Top tray", "displayOrder", 1, "primaryImage", true, "version", firstVersion));
        assertThat(reordered.getStatusCode()).isEqualTo(HttpStatus.OK);
        long returnedVersion = versionFor(reordered.getBody(), firstId);
        assertThat(json(
                                fixture.owner(),
                                HttpMethod.PUT,
                                "/api/v1/media/" + firstId + "/layout",
                                Map.of(
                                        "caption",
                                        "Top tray",
                                        "displayOrder",
                                        0,
                                        "primaryImage",
                                        true,
                                        "version",
                                        returnedVersion))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        mediaStorage.failDeletes = true;
        assertThat(restTemplate
                        .exchange(
                                "/api/v1/media/" + secondId,
                                HttpMethod.DELETE,
                                new HttpEntity<>(fixture.owner().headers()),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        mediaStorage.failDeletes = false;
        assertThat(restTemplate
                        .exchange(
                                "/api/v1/media/" + secondId + "/cleanup",
                                HttpMethod.POST,
                                new HttpEntity<>(fixture.owner().headers()),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        String next = upload(fixture.owner(), "/api/v1/assets/" + container + "/media/layout?caption=Middle", PNG)
                .getBody();
        assertThat(next).contains("\"displayOrder\":1");
    }

    private ResponseEntity<String> upload(AuthenticatedSession session, String path, byte[] contents) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedBytes(contents));
        HttpHeaders headers = session.headers();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> json(AuthenticatedSession session, HttpMethod method, String path, Object body) {
        HttpHeaders headers = session.headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private UUID model(Fixture fixture, boolean container) {
        var category = json(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "Media " + UUID.randomUUID(), "color", "#112233"));
        var model = json(
                fixture.owner(),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        "Model " + UUID.randomUUID(),
                        "categoryId",
                        field(category.getBody(), "id"),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        container));
        return UUID.fromString(field(model.getBody(), "id"));
    }

    private UUID asset(Fixture fixture, UUID modelId) {
        return UUID.fromString(field(
                json(
                                fixture.owner(),
                                HttpMethod.POST,
                                "/api/v1/asset-models/" + modelId + "/assets",
                                Map.of("individualName", "Media box"))
                        .getBody(),
                "id"));
    }

    private Fixture fixture() {
        Organization organization = organizationService.ensureOrganizationExists("Media " + UUID.randomUUID());
        var sessions = new EnumMap<OrganizationRole, AuthenticatedSession>(OrganizationRole.class);
        for (OrganizationRole role : OrganizationRole.values()) {
            var now = clock.instant();
            User user = new User(
                    UUID.randomUUID(),
                    role.name().toLowerCase(Locale.ROOT) + UUID.randomUUID(),
                    null,
                    "Media Test",
                    passwordEncoder.encode(PASSWORD),
                    true,
                    now);
            userRepository.save(user);
            membershipRepository.save(
                    new OrganizationMembership(UUID.randomUUID(), organization.getId(), user.getId(), role, now));
            sessions.put(role, PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD));
        }
        return new Fixture(sessions);
    }

    private String field(String json, String name) {
        JsonNode value = json(json).get(name);
        if (value == null || value.isNull()) {
            throw new AssertionError("Response is missing field '" + name + "': " + json);
        }
        return value.asString();
    }

    private long versionFor(String json, String mediaId) {
        for (JsonNode item : json(json)) {
            if (mediaId.equals(item.path("id").asString())) {
                return item.path("version").asLong();
            }
        }
        throw new AssertionError("Response is missing media '" + mediaId + "': " + json);
    }

    private JsonNode json(String response) {
        try {
            return objectMapper.readTree(response);
        } catch (Exception exception) {
            throw new AssertionError("Response is not JSON: " + response, exception);
        }
    }

    private static byte[] createPng() {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0x224466);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer unavailable");
            }
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record Fixture(Map<OrganizationRole, AuthenticatedSession> sessions) {
        AuthenticatedSession owner() {
            return session(OrganizationRole.OWNER);
        }

        AuthenticatedSession session(OrganizationRole role) {
            return sessions.get(role);
        }
    }

    private static final class NamedBytes extends ByteArrayResource {
        private NamedBytes(byte[] bytes) {
            super(bytes);
        }

        @Override
        public String getFilename() {
            return "photo.png";
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MediaStorageTestConfiguration {
        @Bean
        @Primary
        InMemoryMediaStorage testMediaStorage() {
            return new InMemoryMediaStorage();
        }
    }

    static final class InMemoryMediaStorage implements MediaStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private volatile boolean failDeletes;

        @Override
        public void put(String key, String contentType, long length, InputStream input) {
            try {
                objects.put(key, input.readAllBytes());
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public StoredMedia get(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new IllegalStateException("Missing object");
            return new StoredMedia(new ByteArrayInputStream(bytes), "image/png", bytes.length);
        }

        @Override
        public void delete(String key) {
            if (failDeletes) throw new IllegalStateException("Injected deletion failure");
            objects.remove(key);
        }
    }
}

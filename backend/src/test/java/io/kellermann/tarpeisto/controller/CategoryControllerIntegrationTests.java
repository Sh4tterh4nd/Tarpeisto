package io.kellermann.tarpeisto.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.model.ActivityLog;
import io.kellermann.tarpeisto.model.Organization;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.ActivityLogRepository;
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

/**
 * Covers specification section 5: Owner/Deputy administer categories, Operator/Auditor and Viewer
 * cannot mutate them, names are unique per organization while active, color is normalized, and
 * cross-organization records are indistinguishable from missing.
 */
class CategoryControllerIntegrationTests extends AbstractIntegrationTest {

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
    private ActivityLogRepository activityLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @Test
    void hardDeleteIsOwnerOnlyAndMissingAndForeignCategoriesAreEquivalent() {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture, OrganizationRole.OWNER, "Delete me", "#112233");
        for (OrganizationRole role :
                List.of(OrganizationRole.DEPUTY, OrganizationRole.OPERATOR_AUDITOR, OrganizationRole.VIEWER)) {
            assertThat(exchange(fixture.sessionFor(role), HttpMethod.DELETE, "/api/v1/categories/" + categoryId, null)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(exchange(
                                fixture.sessionFor(OrganizationRole.OWNER),
                                HttpMethod.DELETE,
                                "/api/v1/categories/" + categoryId,
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(categoryRepository.findById(categoryId)).isEmpty();
        assertThat(activityLogRepository.findAllByOrganizationIdOrderByOccurredAtDesc(
                        fixture.organization().getId()))
                .filteredOn(row -> row.getAction().equals("CATEGORY_DELETED"))
                .singleElement()
                .satisfies(row -> assertThat(row.getDetail()).contains("Delete me", "#112233"));
        Fixture foreign = fixtureWithAllRoles();
        UUID foreignId = createCategory(foreign, OrganizationRole.OWNER, "Foreign", "#112233");
        for (UUID id : List.of(foreignId, UUID.randomUUID())) {
            var response = exchange(
                    fixture.sessionFor(OrganizationRole.OWNER), HttpMethod.DELETE, "/api/v1/categories/" + id, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).contains("Category not found.");
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = OrganizationRole.class,
            names = {"OPERATOR_AUDITOR", "VIEWER"})
    void creatingACategoryIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();

        var response = exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "Attempted-" + UUID.randomUUID(), "color", "#112233"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(
            value = OrganizationRole.class,
            names = {"OPERATOR_AUDITOR", "VIEWER"})
    void renamingACategoryIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture, OrganizationRole.OWNER, "Networking", "#112233");

        var response = exchange(
                fixture.sessionFor(role),
                HttpMethod.PUT,
                "/api/v1/categories/" + categoryId,
                Map.of("name", "Renamed", "color", "#445566"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(
            value = OrganizationRole.class,
            names = {"OPERATOR_AUDITOR", "VIEWER"})
    void archivingACategoryIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();
        UUID categoryId = createCategory(fixture, OrganizationRole.OWNER, "Networking", "#112233");

        var response = exchange(
                fixture.sessionFor(role), HttpMethod.POST, "/api/v1/categories/" + categoryId + "/archive", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void everyRoleIncludingViewerCanListCategories() {
        Fixture fixture = fixtureWithAllRoles();
        createCategory(fixture, OrganizationRole.OWNER, "Networking", "#112233");

        for (OrganizationRole role : OrganizationRole.values()) {
            var response = exchange(fixture.sessionFor(role), HttpMethod.GET, "/api/v1/categories", null);
            assertThat(response.getStatusCode()).as("role %s", role).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as("role %s", role).contains("Networking");
        }
    }

    @Test
    void ownerCanCreateRenameArchiveAndRestoreAndActivityIsRecorded() {
        Fixture fixture = fixtureWithAllRoles();
        AuthenticatedSession ownerSession = fixture.sessionFor(OrganizationRole.OWNER);

        var createResponse = exchange(
                ownerSession, HttpMethod.POST, "/api/v1/categories", Map.of("name", "Networking", "color", "ff00aa"));
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).contains("\"color\":\"#FF00AA\"");
        UUID categoryId = UUID.fromString(extractField(createResponse.getBody(), "id"));

        var renameResponse = exchange(
                ownerSession,
                HttpMethod.PUT,
                "/api/v1/categories/" + categoryId,
                Map.of("name", "Cables", "color", "#00FF00"));
        assertThat(renameResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(categoryRepository.findById(categoryId).orElseThrow().getName())
                .isEqualTo("Cables");

        var archiveResponse =
                exchange(ownerSession, HttpMethod.POST, "/api/v1/categories/" + categoryId + "/archive", null);
        assertThat(archiveResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(categoryRepository.findById(categoryId).orElseThrow().isArchived())
                .isTrue();

        var restoreResponse =
                exchange(ownerSession, HttpMethod.POST, "/api/v1/categories/" + categoryId + "/restore", null);
        assertThat(restoreResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(categoryRepository.findById(categoryId).orElseThrow().isArchived())
                .isFalse();

        List<ActivityLog> entries = activityLogRepository.findAllByOrganizationIdOrderByOccurredAtDesc(
                fixture.organization().getId());
        assertThat(entries)
                .filteredOn(entry ->
                        entry.getTargetId() != null && entry.getTargetId().equals(categoryId))
                .extracting(ActivityLog::getAction)
                .contains("CATEGORY_CREATED", "CATEGORY_RENAMED", "CATEGORY_ARCHIVED", "CATEGORY_RESTORED");
    }

    @Test
    void deputyCanCreateACategory() {
        Fixture fixture = fixtureWithAllRoles();

        var response = exchange(
                fixture.sessionFor(OrganizationRole.DEPUTY),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "Deputy Category", "color", "#123456"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void creatingACategoryWithADuplicateNameIsRejected() {
        Fixture fixture = fixtureWithAllRoles();
        createCategory(fixture, OrganizationRole.OWNER, "Networking", "#112233");

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "networking", "color", "#445566"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"errorCode\":\"VALIDATION_FAILED\"");
    }

    @Test
    void aCategoryNameFreedByArchivingCanBeReused() {
        Fixture fixture = fixtureWithAllRoles();
        AuthenticatedSession ownerSession = fixture.sessionFor(OrganizationRole.OWNER);
        UUID categoryId = createCategory(fixture, OrganizationRole.OWNER, "Networking", "#112233");
        exchange(ownerSession, HttpMethod.POST, "/api/v1/categories/" + categoryId + "/archive", null);

        var response = exchange(
                ownerSession, HttpMethod.POST, "/api/v1/categories", Map.of("name", "Networking", "color", "#445566"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aCategoryFromAnotherOrganizationIsNotVisibleOrMutable() {
        Fixture organizationA = fixtureWithAllRoles();
        Fixture organizationB = fixtureWithAllRoles();
        UUID categoryId = createCategory(organizationA, OrganizationRole.OWNER, "Networking", "#112233");

        var listResponse =
                exchange(organizationB.sessionFor(OrganizationRole.OWNER), HttpMethod.GET, "/api/v1/categories", null);
        assertThat(listResponse.getBody()).doesNotContain("Networking");

        var renameResponse = exchange(
                organizationB.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/categories/" + categoryId,
                Map.of("name", "Hijacked", "color", "#000000"));
        assertThat(renameResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private UUID createCategory(Fixture fixture, OrganizationRole creatorRole, String name, String color) {
        var response = exchange(
                fixture.sessionFor(creatorRole),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", name, "color", color));
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
                organizationService.ensureOrganizationExists("Category Test Org " + UUID.randomUUID());
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

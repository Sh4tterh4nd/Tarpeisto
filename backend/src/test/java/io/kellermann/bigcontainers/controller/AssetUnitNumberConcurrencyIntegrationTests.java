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
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Covers specification section 8.2 concurrency-safety: two genuinely concurrent bulk-creation
 * requests against the <strong>same</strong> asset model must never be assigned overlapping
 * model-local unit numbers.
 *
 * <p>This deliberately uses real threads issuing real, simultaneous HTTP requests (backed by
 * {@code AssetUnitNumberSequenceRepository}'s atomic {@code UPDATE ... RETURNING} allocation against
 * PostgreSQL) rather than two sequential calls made to look concurrent - a sequential test would
 * pass even if the allocation had no locking at all.
 */
class AssetUnitNumberConcurrencyIntegrationTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBattery1";
    private static final int THREADS = 4;
    private static final int COUNT_PER_THREAD = 25;

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

    @Test
    void concurrentBulkCreationAgainstTheSameModelNeverDuplicatesUnitNumbers() throws Exception {
        Organization organization =
                organizationService.ensureOrganizationExists("Concurrency Test Org " + UUID.randomUUID());
        User owner = createOwner(organization);
        AuthenticatedSession session = PermissionTestSupport.login(restTemplate, owner.getUsername(), PASSWORD);

        UUID categoryId = createCategory(session);
        UUID modelId = createSerializedModel(session, categoryId);

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch readyLatch = new CountDownLatch(THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await();
                return bulkCreate(session, modelId, COUNT_PER_THREAD);
            });
        }

        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (Callable<ResponseEntity<String>> task : tasks) {
            futures.add(executor.submit(task));
        }
        readyLatch.await();
        startLatch.countDown();

        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            responses.add(future.get(60, TimeUnit.SECONDS));
        }
        executor.shutdown();

        for (ResponseEntity<String> response : responses) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        List<Integer> allUnitNumbers = responses.stream()
                .flatMap(response -> extractIntFields(response.getBody(), "unitNumber").stream())
                .collect(Collectors.toList());

        int expectedTotal = THREADS * COUNT_PER_THREAD;
        assertThat(allUnitNumbers).hasSize(expectedTotal);
        assertThat(allUnitNumbers).doesNotHaveDuplicates();
        assertThat(allUnitNumbers)
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.rangeClosed(1, expectedTotal).boxed().toList());
    }

    private ResponseEntity<String> bulkCreate(AuthenticatedSession session, UUID modelId, int count) {
        var headers = session.headers();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/api/v1/asset-models/" + modelId + "/assets/bulk",
                HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("count", count), headers),
                String.class);
    }

    private UUID createCategory(AuthenticatedSession session) {
        var headers = session.headers();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var response = restTemplate.exchange(
                "/api/v1/categories",
                HttpMethod.POST,
                new HttpEntity<>(
                        java.util.Map.of("name", "Networking-" + UUID.randomUUID(), "color", "#112233"), headers),
                String.class);
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private UUID createSerializedModel(AuthenticatedSession session, UUID categoryId) {
        var headers = session.headers();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var response = restTemplate.exchange(
                "/api/v1/asset-models",
                HttpMethod.POST,
                new HttpEntity<>(
                        java.util.Map.of(
                                "name",
                                "LAN 10m-" + UUID.randomUUID(),
                                "categoryId",
                                categoryId.toString(),
                                "trackingMode",
                                "SERIALIZED_ASSET",
                                "canContainAssets",
                                false),
                        headers),
                String.class);
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private User createOwner(Organization organization) {
        var now = clock.instant();
        User user = new User(
                UUID.randomUUID(),
                "owner-" + UUID.randomUUID(),
                null,
                "Test Owner",
                passwordEncoder.encode(PASSWORD),
                true,
                now);
        userRepository.save(user);
        membershipRepository.save(new OrganizationMembership(
                UUID.randomUUID(), organization.getId(), user.getId(), OrganizationRole.OWNER, now));
        return user;
    }

    private static String extractField(String json, String fieldName) {
        var matcher = Pattern.compile("\"" + fieldName + "\":\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<Integer> extractIntFields(String json, String fieldName) {
        List<Integer> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"" + fieldName + "\":(-?\\d+)").matcher(json);
        while (matcher.find()) {
            values.add(Integer.valueOf(matcher.group(1)));
        }
        return values;
    }
}

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
import java.math.BigDecimal;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The mandatory concurrency guarantee (implementation plan section 4.6: "Concurrent issues cannot
 * spend the same available stock twice"; section 18's risk row: "lock affected balances
 * transactionally, reject negative results, and test concurrent issue paths against PostgreSQL").
 *
 * <p>This uses real threads issuing real, simultaneous HTTP requests against a real PostgreSQL
 * instance - not two sequential calls dressed up as concurrency, which would pass even with no
 * locking at all. {@code CONCURRENT_ISSUERS} threads are released together (via a {@link
 * CountDownLatch} start barrier, after all of them have confirmed readiness through a second
 * latch) to all attempt to issue the entire balance at the same moment. {@code
 * ConsumableStockLedgerRepository}'s atomic {@code UPDATE ... WHERE quantity + delta >= 0} (see its
 * Javadoc) must let exactly one of them win.
 */
class ConsumableStockConcurrencyIntegrationTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBattery1";
    private static final int CONCURRENT_ISSUERS = 8;
    private static final BigDecimal STARTING_BALANCE = new BigDecimal("100.000");

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
    void twoConcurrentIssuesForTheFullBalanceLetExactlyOneSucceed() throws Exception {
        Organization organization =
                organizationService.ensureOrganizationExists("Consumable Concurrency Test Org " + UUID.randomUUID());
        User owner = createOwner(organization);
        AuthenticatedSession session = PermissionTestSupport.login(restTemplate, owner.getUsername(), PASSWORD);

        UUID modelId = createQuantityModel(session);
        UUID containerId = createContainer(session);
        receive(session, modelId, containerId, STARTING_BALANCE);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_ISSUERS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_ISSUERS);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_ISSUERS; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await();
                return issue(session, modelId, containerId, STARTING_BALANCE);
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

        long successCount = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.OK)
                .count();
        long conflictCount = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .count();

        // The whole point of the guarantee: with a balance of N and CONCURRENT_ISSUERS concurrent
        // issues of N each, exactly one may succeed - never zero (the lock must not simply serialize
        // into all-succeed by some accounting bug) and never more than one (that would be double
        // spending the same stock).
        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(CONCURRENT_ISSUERS - 1);

        var balanceListing =
                exchange(session, HttpMethod.GET, "/api/v1/asset-models/" + modelId + "/consumable-stock", null);
        BigDecimal finalBalance = new BigDecimal(extractNumberField(balanceListing.getBody(), "quantity"));

        // The final balance must never be negative, and must reconcile exactly with the ledger: one
        // receipt of STARTING_BALANCE and exactly one successful issue of STARTING_BALANCE nets zero.
        assertThat(finalBalance.signum()).isGreaterThanOrEqualTo(0);
        assertThat(finalBalance).isEqualByComparingTo(BigDecimal.ZERO);

        String balanceId = extractField(balanceListing.getBody(), "id");
        var ledger = exchange(session, HttpMethod.GET, "/api/v1/consumable-stock/" + balanceId + "/movements", null);
        List<BigDecimal> deltas = extractNumberFields(ledger.getBody(), "quantityDelta").stream()
                .map(BigDecimal::new)
                .toList();
        assertThat(deltas).hasSize(2);
        BigDecimal sumOfDeltas = deltas.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumOfDeltas).isEqualByComparingTo(finalBalance);
    }

    private ResponseEntity<String> receive(
            AuthenticatedSession session, UUID modelId, UUID containerId, BigDecimal quantity) {
        return exchange(
                session,
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/consumable-stock/receive",
                java.util.Map.of("containerAssetId", containerId.toString(), "quantity", quantity, "note", ""));
    }

    private ResponseEntity<String> issue(
            AuthenticatedSession session, UUID modelId, UUID containerId, BigDecimal quantity) {
        return exchange(
                session,
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/consumable-stock/issue",
                java.util.Map.of("containerAssetId", containerId.toString(), "quantity", quantity, "note", ""));
    }

    private UUID createCategory(AuthenticatedSession session) {
        var response = exchange(
                session,
                HttpMethod.POST,
                "/api/v1/categories",
                java.util.Map.of("name", "Consumables-" + UUID.randomUUID(), "color", "#112233"));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private UUID createQuantityModel(AuthenticatedSession session) {
        UUID categoryId = createCategory(session);
        var response = exchange(
                session,
                HttpMethod.POST,
                "/api/v1/asset-models",
                java.util.Map.of(
                        "name",
                        "Gaffer tape-" + UUID.randomUUID(),
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

    private UUID createContainer(AuthenticatedSession session) {
        UUID categoryId = createCategory(session);
        var modelResponse = exchange(
                session,
                HttpMethod.POST,
                "/api/v1/asset-models",
                java.util.Map.of(
                        "name",
                        "Flightcase-" + UUID.randomUUID(),
                        "categoryId",
                        categoryId.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        true));
        UUID modelId = UUID.fromString(extractField(modelResponse.getBody(), "id"));

        var assetResponse = exchange(
                session,
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets",
                java.util.Map.of("individualName", "Flightcase-" + UUID.randomUUID(), "values", List.of()));
        return UUID.fromString(extractField(assetResponse.getBody(), "id"));
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

    private ResponseEntity<String> exchange(AuthenticatedSession session, HttpMethod method, String path, Object body) {
        var headers = session.headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String extractField(String json, String fieldName) {
        var matcher = Pattern.compile("\"" + fieldName + "\":\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String extractNumberField(String json, String fieldName) {
        var matcher = Pattern.compile("\"" + fieldName + "\":(-?[0-9.]+)").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> extractNumberFields(String json, String fieldName) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"" + fieldName + "\":(-?[0-9.]+)").matcher(json);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}

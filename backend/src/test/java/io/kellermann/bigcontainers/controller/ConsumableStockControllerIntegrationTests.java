package io.kellermann.bigcontainers.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Covers specification section 6.4: the role matrix (only Owner/Deputy can change a balance), a
 * {@code SERIALIZED_ASSET} model rejected for a balance, cross-organization isolation, every
 * movement reason writing a correct ledger entry, transfer atomicity (a forced failure on the
 * destination side leaves the source unchanged), rejection of a negative result, movement
 * immutability at the database level, ledger-to-balance reconciliation, and low-stock derivation.
 *
 * <p>The mandatory concurrent-issue race (plan section 4.6/18) is covered separately in {@link
 * ConsumableStockConcurrencyIntegrationTests} - that scenario needs real parallel threads, not
 * sequential HTTP calls.
 */
class ConsumableStockControllerIntegrationTests extends AbstractIntegrationTest {

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private Clock clock;

    @ParameterizedTest
    @EnumSource(
            value = OrganizationRole.class,
            names = {"OPERATOR_AUDITOR", "VIEWER"})
    void balanceChangingActionsAreDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(fixture, "Gaffer tape", null);
        UUID containerId = createContainer(fixture, "Flightcase");

        assertThat(receive(fixture, role, modelId, containerId, "10", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adjust(fixture, role, modelId, containerId, "5", "MANUAL_ADJUSTMENT", "Stock count correction")
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void everyRoleCanReadBalancesAndTheLedger() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(fixture, "Gaffer tape", null);
        UUID containerId = createContainer(fixture, "Flightcase");
        var receipt = receive(fixture, OrganizationRole.OWNER, modelId, containerId, "10", "Initial stock");
        UUID balanceId = UUID.fromString(extractField(receipt.getBody(), "id"));

        for (OrganizationRole role : OrganizationRole.values()) {
            assertThat(exchange(
                                    fixture.sessionFor(role),
                                    HttpMethod.GET,
                                    "/api/v1/asset-models/" + modelId + "/consumable-stock",
                                    null)
                            .getStatusCode())
                    .as("list for role %s", role)
                    .isEqualTo(HttpStatus.OK);
            assertThat(exchange(fixture.sessionFor(role), HttpMethod.GET, "/api/v1/consumable-stock/" + balanceId, null)
                            .getStatusCode())
                    .as("get for role %s", role)
                    .isEqualTo(HttpStatus.OK);
            assertThat(exchange(
                                    fixture.sessionFor(role),
                                    HttpMethod.GET,
                                    "/api/v1/consumable-stock/" + balanceId + "/movements",
                                    null)
                            .getStatusCode())
                    .as("movements for role %s", role)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void aSerializedAssetModelCannotHaveAConsumableStockBalance() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createSerializedModel(fixture, "UniFi AP-HD");
        UUID containerId = createContainer(fixture, "Flightcase");

        var response = receive(fixture, OrganizationRole.OWNER, modelId, containerId, "5", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void receiptTransferIssueReturnConsumptionAndAdjustmentEachWriteACorrectMovement() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(fixture, "Gaffer tape", null);
        UUID sourceContainer = createContainer(fixture, "Flightcase A");
        UUID destinationContainer = createContainer(fixture, "Flightcase B");

        var receiptResponse =
                receive(fixture, OrganizationRole.OWNER, modelId, sourceContainer, "20", "Initial delivery");
        assertThat(receiptResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID sourceBalanceId = UUID.fromString(extractField(receiptResponse.getBody(), "id"));
        assertThat(receiptResponse.getBody()).contains("\"quantity\":20");

        var transferResponse =
                transfer(fixture, OrganizationRole.DEPUTY, modelId, sourceContainer, destinationContainer, "8");
        assertThat(transferResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID destinationBalanceId = UUID.fromString(extractNestedId(transferResponse.getBody(), "destination"));

        var issueResponse = issue(fixture, OrganizationRole.OWNER, modelId, sourceContainer, "3", "Event issue");
        assertThat(issueResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        var returnResponse =
                returnStock(fixture, OrganizationRole.OWNER, modelId, sourceContainer, "1", "Event return");
        assertThat(returnResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        var consumeResponse =
                consume(fixture, OrganizationRole.OWNER, modelId, sourceContainer, "2", "Used during setup");
        assertThat(consumeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        var adjustResponse =
                adjust(fixture, OrganizationRole.OWNER, modelId, sourceContainer, "1", "MANUAL_ADJUSTMENT", "Recount");
        assertThat(adjustResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 20 - 8 (transfer out) - 3 (issue) + 1 (return) - 2 (consume) + 1 (adjust) = 9
        assertThat(adjustResponse.getBody()).contains("\"quantity\":9");

        var ledger = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.GET,
                "/api/v1/consumable-stock/" + sourceBalanceId + "/movements",
                null);
        assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> reasons = extractStringFields(ledger.getBody(), "reason");
        assertThat(reasons)
                .containsExactlyInAnyOrder(
                        "RECEIPT", "TRANSFER", "EVENT_ISSUE", "EVENT_RETURN", "CONSUMPTION", "MANUAL_ADJUSTMENT");

        List<BigDecimal> deltas = extractStringFields(ledger.getBody(), "quantityDelta").stream()
                .map(BigDecimal::new)
                .toList();
        BigDecimal sum = deltas.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("9");

        var destinationLedger = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.GET,
                "/api/v1/consumable-stock/" + destinationBalanceId + "/movements",
                null);
        assertThat(extractStringFields(destinationLedger.getBody(), "reason")).containsExactly("TRANSFER");
        assertThat(destinationLedger.getBody()).contains("\"quantityDelta\":8");
    }

    @Test
    void anOperationCannotMakeABalanceNegative() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(fixture, "Gaffer tape", null);
        UUID containerId = createContainer(fixture, "Flightcase");
        receive(fixture, OrganizationRole.OWNER, modelId, containerId, "5", null);

        var response = issue(fixture, OrganizationRole.OWNER, modelId, containerId, "10", "Too much");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var balance = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.GET,
                "/api/v1/asset-models/" + modelId + "/consumable-stock",
                null);
        assertThat(balance.getBody()).contains("\"quantity\":5");
    }

    @Test
    void issuingAgainstAPlaceWithNoBalanceYetIsAlsoInsufficientStock() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(fixture, "Gaffer tape", null);
        UUID containerId = createContainer(fixture, "Flightcase");

        var response = issue(fixture, OrganizationRole.OWNER, modelId, containerId, "1", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void transferAtomicityForcedFailureOnDestinationLeavesSourceUnchanged() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(fixture, "Gaffer tape", null);
        UUID sourceContainer = createContainer(fixture, "Flightcase A");
        UUID destinationContainer = createContainer(fixture, "Flightcase B");

        receive(fixture, OrganizationRole.OWNER, modelId, sourceContainer, "100", null);
        // NUMERIC(14,3) allows at most 11 integer digits; seed the destination right at that edge so
        // adding even a small amount overflows only the destination side's arithmetic.
        receive(fixture, OrganizationRole.OWNER, modelId, destinationContainer, "99999999999.999", null);

        var response = transfer(fixture, OrganizationRole.OWNER, modelId, sourceContainer, destinationContainer, "100");
        assertThat(response.getStatusCode().is5xxServerError()).isTrue();

        var balances = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.GET,
                "/api/v1/asset-models/" + modelId + "/consumable-stock",
                null);
        // The source balance must be exactly what it was before the failed transfer - not
        // decremented - because the whole operation rolled back together with the destination side.
        assertThat(balances.getBody()).contains("\"quantity\":100");
        assertThat(balances.getBody()).contains("\"quantity\":99999999999.999");
    }

    @Test
    void theLedgerReconcilesWithTheBalanceAfterSeveralMovements() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(fixture, "Gaffer tape", null);
        UUID containerId = createContainer(fixture, "Flightcase");

        receive(fixture, OrganizationRole.OWNER, modelId, containerId, "50", null);
        issue(fixture, OrganizationRole.OWNER, modelId, containerId, "12", null);
        var last = adjust(
                fixture, OrganizationRole.OWNER, modelId, containerId, "-3", "MANUAL_ADJUSTMENT", "Damage write-off");
        UUID balanceId = UUID.fromString(extractField(last.getBody(), "id"));
        BigDecimal finalQuantity = new BigDecimal(extractNumberField(last.getBody(), "quantity"));

        var ledger = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.GET,
                "/api/v1/consumable-stock/" + balanceId + "/movements",
                null);
        List<BigDecimal> deltas = extractStringFields(ledger.getBody(), "quantityDelta").stream()
                .map(BigDecimal::new)
                .toList();
        BigDecimal sumOfDeltas = deltas.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sumOfDeltas).isEqualByComparingTo(finalQuantity);
        assertThat(sumOfDeltas).isEqualByComparingTo("35");

        // The most recent movement's resultingQuantity must also match the current balance.
        String mostRecentResultingQuantity =
                extractStringFields(ledger.getBody(), "resultingQuantity").get(0);
        assertThat(new BigDecimal(mostRecentResultingQuantity)).isEqualByComparingTo(finalQuantity);
    }

    @Test
    void stockMovementRowsAreImmutableAtTheDatabaseLevel() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(fixture, "Gaffer tape", null);
        UUID containerId = createContainer(fixture, "Flightcase");
        receive(fixture, OrganizationRole.OWNER, modelId, containerId, "10", null);

        assertThatThrownBy(() -> jdbcClient
                        .sql("UPDATE stock_movement SET note = 'tampered' WHERE 1 = 1")
                        .update())
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() ->
                        jdbcClient.sql("DELETE FROM stock_movement WHERE 1 = 1").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void lowStockIsCalculatedNotStored() {
        Fixture fixture = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(fixture, "Cable ties", "10");
        UUID containerId = createContainer(fixture, "Bin 1");

        receive(fixture, OrganizationRole.OWNER, modelId, containerId, "5", null);
        var lowAfterFirstReceipt = exchange(
                fixture.sessionFor(OrganizationRole.OWNER), HttpMethod.GET, "/api/v1/consumable-stock/low-stock", null);
        assertThat(extractStringFields(lowAfterFirstReceipt.getBody(), "assetModelId"))
                .contains(modelId.toString());

        receive(fixture, OrganizationRole.OWNER, modelId, containerId, "20", null);
        var lowAfterSecondReceipt = exchange(
                fixture.sessionFor(OrganizationRole.OWNER), HttpMethod.GET, "/api/v1/consumable-stock/low-stock", null);
        assertThat(extractStringFields(lowAfterSecondReceipt.getBody(), "assetModelId"))
                .doesNotContain(modelId.toString());
    }

    @Test
    void aBalanceFromAnotherOrganizationIsNotVisible() {
        Fixture organizationA = fixtureWithAllRoles();
        Fixture organizationB = fixtureWithAllRoles();
        UUID modelId = createQuantityModel(organizationA, "Gaffer tape", null);
        UUID containerId = createContainer(organizationA, "Flightcase");
        var receipt = receive(organizationA, OrganizationRole.OWNER, modelId, containerId, "10", null);
        UUID balanceId = UUID.fromString(extractField(receipt.getBody(), "id"));

        var getResponse = exchange(
                organizationB.sessionFor(OrganizationRole.OWNER),
                HttpMethod.GET,
                "/api/v1/consumable-stock/" + balanceId,
                null);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var receiveResponse = receive(organizationB, OrganizationRole.OWNER, modelId, containerId, "1", null);
        assertThat(receiveResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> receive(
            Fixture fixture, OrganizationRole role, UUID modelId, UUID containerId, String quantity, String note) {
        return exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/consumable-stock/receive",
                Map.of(
                        "containerAssetId",
                        containerId.toString(),
                        "quantity",
                        new BigDecimal(quantity),
                        "note",
                        stringOrEmpty(note)));
    }

    private ResponseEntity<String> issue(
            Fixture fixture, OrganizationRole role, UUID modelId, UUID containerId, String quantity, String note) {
        return exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/consumable-stock/issue",
                Map.of(
                        "containerAssetId",
                        containerId.toString(),
                        "quantity",
                        new BigDecimal(quantity),
                        "note",
                        stringOrEmpty(note)));
    }

    private ResponseEntity<String> returnStock(
            Fixture fixture, OrganizationRole role, UUID modelId, UUID containerId, String quantity, String note) {
        return exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/consumable-stock/return",
                Map.of(
                        "containerAssetId",
                        containerId.toString(),
                        "quantity",
                        new BigDecimal(quantity),
                        "note",
                        stringOrEmpty(note)));
    }

    private ResponseEntity<String> consume(
            Fixture fixture, OrganizationRole role, UUID modelId, UUID containerId, String quantity, String note) {
        return exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/consumable-stock/consume",
                Map.of(
                        "containerAssetId",
                        containerId.toString(),
                        "quantity",
                        new BigDecimal(quantity),
                        "note",
                        stringOrEmpty(note)));
    }

    private ResponseEntity<String> transfer(
            Fixture fixture,
            OrganizationRole role,
            UUID modelId,
            UUID sourceContainerId,
            UUID destinationContainerId,
            String quantity) {
        return exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/consumable-stock/transfer",
                Map.of(
                        "sourceContainerAssetId",
                        sourceContainerId.toString(),
                        "destinationContainerAssetId",
                        destinationContainerId.toString(),
                        "quantity",
                        new BigDecimal(quantity)));
    }

    private ResponseEntity<String> adjust(
            Fixture fixture,
            OrganizationRole role,
            UUID modelId,
            UUID containerId,
            String delta,
            String type,
            String reason) {
        return exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/consumable-stock/adjust",
                Map.of(
                        "containerAssetId",
                        containerId.toString(),
                        "delta",
                        new BigDecimal(delta),
                        "type",
                        type,
                        "reason",
                        reason));
    }

    private UUID createCategory(Fixture fixture) {
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/categories",
                Map.of("name", "Consumables-" + UUID.randomUUID(), "color", "#112233"));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private UUID createQuantityModel(Fixture fixture, String name, String lowStockThreshold) {
        UUID categoryId = createCategory(fixture);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name + "-" + UUID.randomUUID());
        body.put("categoryId", categoryId.toString());
        body.put("trackingMode", "QUANTITY_STOCK");
        body.put("stockUnitLabel", "roll");
        body.put("canContainAssets", false);
        if (lowStockThreshold != null) {
            body.put("lowStockThreshold", new BigDecimal(lowStockThreshold));
        }
        var response =
                exchange(fixture.sessionFor(OrganizationRole.OWNER), HttpMethod.POST, "/api/v1/asset-models", body);
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    private UUID createSerializedModel(Fixture fixture, String name) {
        UUID categoryId = createCategory(fixture);
        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
                        "name",
                        name + "-" + UUID.randomUUID(),
                        "categoryId",
                        categoryId.toString(),
                        "trackingMode",
                        "SERIALIZED_ASSET",
                        "canContainAssets",
                        false));
        return UUID.fromString(extractField(response.getBody(), "id"));
    }

    /** A container-capable serialized model with exactly one named unit, usable as a stock place. */
    private UUID createContainer(Fixture fixture, String individualName) {
        UUID categoryId = createCategory(fixture);
        var modelResponse = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models",
                Map.of(
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
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.POST,
                "/api/v1/asset-models/" + modelId + "/assets",
                Map.of("individualName", individualName + "-" + UUID.randomUUID(), "values", List.of()));
        return UUID.fromString(extractField(assetResponse.getBody(), "id"));
    }

    private static String stringOrEmpty(String value) {
        return value == null ? "" : value;
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

    /** Extracts the {@code "id"} of a nested object property, such as {@code "destination":{"id":"..."}}. */
    private static String extractNestedId(String json, String parentFieldName) {
        var matcher = Pattern.compile("\"" + parentFieldName + "\":\\{\"id\":\"([^\"]+)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String extractNumberField(String json, String fieldName) {
        var matcher = Pattern.compile("\"" + fieldName + "\":(-?[0-9.]+)").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> extractStringFields(String json, String fieldName) {
        List<String> values = new ArrayList<>();
        Matcher stringMatcher =
                Pattern.compile("\"" + fieldName + "\":\"([^\"]*)\"").matcher(json);
        while (stringMatcher.find()) {
            values.add(stringMatcher.group(1));
        }
        if (!values.isEmpty()) {
            return values;
        }
        Matcher numberMatcher =
                Pattern.compile("\"" + fieldName + "\":(-?[0-9.]+)").matcher(json);
        while (numberMatcher.find()) {
            values.add(numberMatcher.group(1));
        }
        return values;
    }

    private Fixture fixtureWithAllRoles() {
        Organization organization =
                organizationService.ensureOrganizationExists("Consumable Stock Test Org " + UUID.randomUUID());
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

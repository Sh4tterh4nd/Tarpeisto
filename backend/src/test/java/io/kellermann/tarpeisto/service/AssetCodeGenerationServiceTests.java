package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.kellermann.tarpeisto.model.AssetCode;
import io.kellermann.tarpeisto.model.AssetCodeValidation;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test: no Spring context. Covers generation producing structurally valid codes and the
 * database collision-retry behavior required by implementation plan section 4.2 and ADR-0002
 * ("A unique-constraint violation causes regeneration and retry with a bounded attempt count"),
 * using a scripted {@link AssetCodeUniquenessChecker} stand-in rather than a real repository -
 * Phase 2a intentionally has no {@code physical_asset} table yet.
 */
class AssetCodeGenerationServiceTests {

    private final AssetCodeGenerationService service = new AssetCodeGenerationService(new SecureRandom());

    @Test
    void generatesAStructurallyValidCode() {
        AssetCode code = service.generate(UUID.randomUUID(), (organizationId, publicCode) -> false);

        assertThat(AssetCode.validate(code.value())).isInstanceOf(AssetCodeValidation.Valid.class);
    }

    @Test
    void generatingManyCodesRemainsUniqueAgainstPreviouslyIssuedCodes() {
        Set<String> seen = new HashSet<>();
        UUID organizationId = UUID.randomUUID();
        for (int i = 0; i < 1_000; i++) {
            AssetCode code =
                    service.generate(organizationId, (checkedOrganizationId, publicCode) -> seen.contains(publicCode));
            assertThat(seen.add(code.value()))
                    .as("code %s should be unique", code.value())
                    .isTrue();
        }
    }

    @Test
    void retriesOnACollisionAndReturnsTheFirstNonCollidingCandidate() {
        AtomicInteger callCount = new AtomicInteger();
        AssetCodeUniquenessChecker checker = (organizationId, publicCode) -> callCount.incrementAndGet() <= 3;

        AssetCode code = service.generate(UUID.randomUUID(), checker);

        assertThat(AssetCode.validate(code.value())).isInstanceOf(AssetCodeValidation.Valid.class);
        assertThat(callCount.get()).isEqualTo(4);
    }

    @Test
    void givesUpAfterTheBoundedAttemptCountWhenEveryCandidateCollides() {
        AtomicInteger callCount = new AtomicInteger();
        AssetCodeUniquenessChecker alwaysCollides = (organizationId, publicCode) -> {
            callCount.incrementAndGet();
            return true;
        };

        assertThatIllegalStateException()
                .isThrownBy(() -> service.generate(UUID.randomUUID(), alwaysCollides))
                .withMessageContaining(String.valueOf(AssetCodeGenerationService.MAX_ATTEMPTS));
        assertThat(callCount.get()).isEqualTo(AssetCodeGenerationService.MAX_ATTEMPTS);
    }

    @Test
    void passesTheOrganizationIdThroughToTheUniquenessChecker() {
        UUID organizationId = UUID.randomUUID();
        AtomicInteger sawExpectedOrganization = new AtomicInteger();
        service.generate(organizationId, (checkedOrganizationId, publicCode) -> {
            if (checkedOrganizationId.equals(organizationId)) {
                sawExpectedOrganization.incrementAndGet();
            }
            return false;
        });

        assertThat(sawExpectedOrganization.get()).isEqualTo(1);
    }
}

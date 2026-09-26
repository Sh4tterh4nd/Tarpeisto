package io.kellermann.bigcontainers.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.bigcontainers.service.AutomaticEmailLinkingPolicy.CandidateAccount;
import io.kellermann.bigcontainers.service.AutomaticEmailLinkingPolicy.Decision;
import io.kellermann.bigcontainers.service.AutomaticEmailLinkingPolicy.Reason;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of {@link AutomaticEmailLinkingPolicy}: no Spring context, no database, no
 * provider. Covers every branch of ADR-0003's automatic email-linking policy (implementation plan
 * section 3.3: "Unverified or ambiguous email claims cannot auto-link accounts").
 */
class AutomaticEmailLinkingPolicyTests {

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void missingEmailIsRejected() {
        Decision decision = AutomaticEmailLinkingPolicy.decide(true, null, List.of());

        assertThat(decision).isInstanceOf(Decision.Reject.class);
        assertThat(((Decision.Reject) decision).reason()).isEqualTo(Reason.EMAIL_MISSING);
    }

    @Test
    void blankEmailIsRejectedAsMissing() {
        Decision decision = AutomaticEmailLinkingPolicy.decide(true, "   ", List.of());

        assertThat(((Decision.Reject) decision).reason()).isEqualTo(Reason.EMAIL_MISSING);
    }

    @Test
    void unverifiedEmailIsRejectedEvenWithExactlyOneMatch() {
        Decision decision = AutomaticEmailLinkingPolicy.decide(
                false, "owner@example.org", List.of(new CandidateAccount(USER_ID, true)));

        assertThat(((Decision.Reject) decision).reason()).isEqualTo(Reason.EMAIL_NOT_VERIFIED);
    }

    @Test
    void noMatchingAccountIsRejected() {
        Decision decision = AutomaticEmailLinkingPolicy.decide(true, "nobody@example.org", List.of());

        assertThat(((Decision.Reject) decision).reason()).isEqualTo(Reason.NO_MATCHING_ACCOUNT);
    }

    @Test
    void twoMatchingAccountsAreRejectedAsAmbiguous() {
        Decision decision = AutomaticEmailLinkingPolicy.decide(
                true,
                "shared@example.org",
                List.of(new CandidateAccount(UUID.randomUUID(), true), new CandidateAccount(UUID.randomUUID(), true)));

        assertThat(((Decision.Reject) decision).reason()).isEqualTo(Reason.AMBIGUOUS_EMAIL_MATCH);
    }

    @Test
    void aDisabledSoleMatchIsRejected() {
        Decision decision = AutomaticEmailLinkingPolicy.decide(
                true, "disabled@example.org", List.of(new CandidateAccount(USER_ID, false)));

        assertThat(((Decision.Reject) decision).reason()).isEqualTo(Reason.ACCOUNT_DISABLED);
    }

    @Test
    void verifiedEmailWithExactlyOneEnabledMatchLinks() {
        Decision decision = AutomaticEmailLinkingPolicy.decide(
                true, "owner@example.org", List.of(new CandidateAccount(USER_ID, true)));

        assertThat(decision).isInstanceOf(Decision.Link.class);
        assertThat(((Decision.Link) decision).userId()).isEqualTo(USER_ID);
    }
}

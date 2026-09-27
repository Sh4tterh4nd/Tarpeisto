package io.kellermann.tarpeisto.service;

import java.util.List;
import java.util.UUID;

/**
 * Pure decision logic for ADR-0003's automatic email-based external-identity linking policy
 * (specification section 4.3/28): "If an Owner enables it, linking succeeds only when: (1) the
 * provider reports {@code email_verified=true}; (2) exactly one eligible internal user has that
 * normalized email; (3) the external identity is not already linked; (4) the internal account is
 * enabled."
 *
 * <p>Condition (3) ("not already linked") is enforced by the caller, {@link
 * ExternalIdentityService#resolveLogin}, before this class is ever consulted: an OIDC login whose
 * {@code (issuer, subject)} already has an active {@link io.kellermann.tarpeisto.model.
 * ExternalIdentity} is a normal returning-user login, not a linking decision at all, so it never
 * reaches this policy.
 *
 * <p>Deliberately a plain, dependency-free, side-effect-free class - no Spring annotation, no
 * repository, no I/O - so every branch is unit-testable without a live provider or a database
 * (implementation plan section 3.3), by constructing {@link CandidateAccount} lists directly. The
 * caller is responsible for normalizing the email and resolving the candidate accounts.
 */
public final class AutomaticEmailLinkingPolicy {

    private AutomaticEmailLinkingPolicy() {}

    /** A closed decision, per docs/DEVELOPMENT_POLICIES.md's preference for sealed outcomes over boolean flags. */
    public sealed interface Decision {
        record Link(UUID userId) implements Decision {}

        record Reject(Reason reason) implements Decision {}
    }

    public enum Reason {
        EMAIL_MISSING,
        EMAIL_NOT_VERIFIED,
        NO_MATCHING_ACCOUNT,
        AMBIGUOUS_EMAIL_MATCH,
        ACCOUNT_DISABLED
    }

    /** One internal user account eligible to be matched by normalized email. */
    public record CandidateAccount(UUID userId, boolean enabled) {}

    /**
     * @param emailVerified the provider's {@code email_verified} claim
     * @param normalizedEmail the provider's {@code email} claim, already normalized (trimmed,
     *     lower-cased) by the caller, or {@code null}/blank if absent
     * @param matchingAccounts every enabled-or-not internal account whose normalized email equals
     *     {@code normalizedEmail}
     */
    public static Decision decide(
            boolean emailVerified, String normalizedEmail, List<CandidateAccount> matchingAccounts) {
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return new Decision.Reject(Reason.EMAIL_MISSING);
        }
        if (!emailVerified) {
            return new Decision.Reject(Reason.EMAIL_NOT_VERIFIED);
        }
        if (matchingAccounts.isEmpty()) {
            return new Decision.Reject(Reason.NO_MATCHING_ACCOUNT);
        }
        if (matchingAccounts.size() > 1) {
            return new Decision.Reject(Reason.AMBIGUOUS_EMAIL_MATCH);
        }
        CandidateAccount only = matchingAccounts.get(0);
        if (!only.enabled()) {
            return new Decision.Reject(Reason.ACCOUNT_DISABLED);
        }
        return new Decision.Link(only.userId());
    }
}

package io.kellermann.tarpeisto.config;

/**
 * The two supported permanent-user authentication deployment modes (ADR-0003, specification
 * section 4.3, implementation plan section 3.2).
 *
 * <p>Local username/password login is available in both modes and is always the recovery path: an
 * installation can additionally accept OIDC logins, but it can never require OIDC only.
 */
public enum AuthenticationMode {
    LOCAL_ONLY,
    LOCAL_AND_OIDC
}

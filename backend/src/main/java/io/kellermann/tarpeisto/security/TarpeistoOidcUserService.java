package io.kellermann.tarpeisto.security;

import io.kellermann.tarpeisto.service.ExternalIdentityService;
import io.kellermann.tarpeisto.service.ExternalIdentityService.OidcLoginOutcome;
import java.util.Map;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Resolves every successful OIDC provider authentication to a Tarpeisto internal user (or
 * rejects it with a specific reason) before any session is created (ADR-0003, specification
 * section 4.3/28).
 *
 * <p>Delegates to the default {@link OidcUserService} first so the ID token is fully validated
 * (issuer, audience, signature, expiry - standard {@code OidcIdTokenDecoderFactory} checks) and the
 * userinfo endpoint is consulted exactly the way Spring Security normally would; this class only
 * adds the Tarpeisto-specific identity resolution on top, in {@link
 * io.kellermann.tarpeisto.service.ExternalIdentityService#resolveLogin}.
 *
 * <p>Just-in-time provisioning is explicitly out of scope: a claim set that cannot be resolved to
 * exactly one existing, eligible internal user throws {@link OAuth2AuthenticationException} with a
 * stable {@link OAuth2Error#getErrorCode()} equal to the {@code
 * ExternalIdentityService.OidcDenialReason} name, which {@link OidcAuthenticationFailureHandler}
 * turns into a same-origin SPA redirect carrying that machine-readable code - never a guess, per
 * ADR-0003's "Ambiguous, missing, or unverified email claims stop with a clear request for Owner
 * assistance."
 */
@Component
class TarpeistoOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();
    private final ExternalIdentityService externalIdentityService;

    TarpeistoOidcUserService(ExternalIdentityService externalIdentityService) {
        this.externalIdentityService = externalIdentityService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser delegateUser = delegate.loadUser(userRequest);

        String issuer = delegateUser.getIdToken().getIssuer().toString();
        String subject = delegateUser.getIdToken().getSubject();
        Map<String, Object> claims = delegateUser.getClaims();
        String email = asString(claims.get("email"));
        boolean emailVerified = Boolean.TRUE.equals(claims.get("email_verified"));
        String displayName = asString(claims.get("name"));

        OidcLoginOutcome outcome =
                externalIdentityService.resolveLogin(issuer, subject, email, emailVerified, displayName);
        return switch (outcome) {
            case OidcLoginOutcome.Authenticated authenticated ->
                new ResolvedOidcUser(delegateUser, authenticated.principal());
            case OidcLoginOutcome.Denied denied -> throw denialToAuthenticationException(denied);
        };
    }

    private static OAuth2AuthenticationException denialToAuthenticationException(OidcLoginOutcome.Denied denied) {
        OAuth2Error error = new OAuth2Error(denied.reason().name(), "OIDC sign-in was not completed.", null);
        return new OAuth2AuthenticationException(error);
    }

    private static String asString(Object claim) {
        return claim == null ? null : claim.toString();
    }
}

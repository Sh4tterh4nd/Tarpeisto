package io.kellermann.bigcontainers.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Component;

/**
 * Deliberately retains no provider access/refresh token anywhere - not in the {@code HttpSession},
 * not in any store - beyond the single login request that needs it (ADR-0003: "BigContainers does
 * not require provider API access for initial login, so tokens should not be retained longer than
 * Spring Security needs"; specification section 28).
 *
 * <p>Without this bean, {@code oauth2Login()} defaults to {@code
 * HttpSessionOAuth2AuthorizedClientRepository}, which would keep the provider's access/refresh
 * token in the (server-side) session for the session's lifetime. BigContainers never calls back
 * into the provider's APIs after login, so there is nothing to gain from that retention and every
 * reason not to: {@link #loadAuthorizedClient} always returns {@code null} and {@link
 * #saveAuthorizedClient} is a no-op, so {@code OAuth2LoginAuthenticationFilter} obtains the tokens
 * only for the duration of processing the callback request and they are discarded immediately
 * after.
 */
@Component
class NoPersistenceOAuth2AuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId, Authentication principal, HttpServletRequest request) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(
            OAuth2AuthorizedClient authorizedClient,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Intentionally not persisted anywhere - see the class Javadoc.
    }

    @Override
    public void removeAuthorizedClient(
            String clientRegistrationId,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Nothing to remove: nothing is ever saved.
    }
}

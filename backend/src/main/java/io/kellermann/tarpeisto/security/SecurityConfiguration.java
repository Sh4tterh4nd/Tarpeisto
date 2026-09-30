package io.kellermann.tarpeisto.security;

import io.kellermann.tarpeisto.config.AuthenticationMode;
import io.kellermann.tarpeisto.config.AuthenticationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer.SessionFixationConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Denies every request by default and explicitly allowlists the small set of endpoints that must
 * be reachable before any user is authenticated.
 *
 * <p>Local username/password login is a JSON API resource ({@code SessionController} / {@code
 * SessionAuthenticationService}) rather than {@code http.formLogin(...)}: the frontend is a
 * same-origin SPA, so login is {@code POST /api/v1/session}, not a server-rendered form.
 *
 * <p>OIDC (ADR-0003) is wired with {@code http.oauth2Login(...)} on top of this same filter chain,
 * session, and CSRF configuration - added only when {@link AuthenticationProperties#mode()} is
 * {@link AuthenticationMode#LOCAL_AND_OIDC}, which {@link AuthenticationProperties}'s own
 * validation has already guaranteed carries a fully usable provider configuration by the time this
 * method runs. See {@link LazyOidcClientRegistrationRepository} for why issuer discovery is
 * deliberately lazy rather than performed here at startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    private static final RequestMatcher API_MATCHER = PathPatternRequestMatcher.pathPattern("/api/**");
    private static final RequestMatcher ACTUATOR_MATCHER = PathPatternRequestMatcher.pathPattern("/actuator/**");

    // Everything that is not under /api or /actuator is the public SPA shell: its HTML, static
    // assets (hashed filenames chosen by the frontend build, not enumerated here) and
    // client-side routes. The SPA itself carries no sensitive data; the API underneath it is the
    // actual authorization boundary.
    private static final RequestMatcher SPA_MATCHER =
            new NegatedRequestMatcher(new OrRequestMatcher(API_MATCHER, ACTUATOR_MATCHER));

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Environment environment,
            ProblemDetailSecurityResponseWriter responseWriter,
            AuthenticationProperties authenticationProperties,
            TarpeistoOidcUserService oidcUserService,
            OidcAuthenticationSuccessHandler oidcAuthenticationSuccessHandler,
            OidcAuthenticationFailureHandler oidcAuthenticationFailureHandler,
            NoPersistenceOAuth2AuthorizedClientRepository noPersistenceOAuth2AuthorizedClientRepository,
            io.kellermann.tarpeisto.service.TemporaryAccessService temporaryAccess,
            io.kellermann.tarpeisto.repository.UserRepository users,
            io.kellermann.tarpeisto.repository.OrganizationMembershipRepository memberships)
            throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(
                        new PrincipalRefreshFilter(temporaryAccess, users, memberships, responseWriter),
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .addFilterAfter(
                        new ExpectedAuditActorFilter(responseWriter),
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .sessionManagement(session ->
                        // Rotates the session identifier on authentication to defeat session
                        // fixation. This is already the Spring Security default; set explicitly
                        // per the development policy that requires it to be visible in code.
                        session.sessionFixation(SessionFixationConfigurer::changeSessionId))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(responseWriter)
                        .accessDeniedHandler(responseWriter))
                .authorizeHttpRequests(authorize -> {
                    authorize
                            .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                            .permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/actuator/info").permitAll();
                    authorize
                            .requestMatchers(HttpMethod.GET, "/api/v1/application")
                            .permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/api/v1/setup").permitAll();
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/setup").permitAll();
                    authorize
                            .requestMatchers(HttpMethod.POST, "/api/v1/temporary-access/redemptions")
                            .permitAll();
                    // The session resource is the JSON-API login/logout endpoint (ADR-0001,
                    // ADR-0003): a caller is by definition not yet authenticated when calling
                    // POST, and DELETE (logout) is intentionally idempotent/safe to call without
                    // an existing session. GET (current principal) is deliberately NOT listed
                    // here: it falls through to the default anyRequest().authenticated() rule
                    // below, so "who am I" always requires an authenticated session.
                    authorize
                            .requestMatchers(HttpMethod.POST, "/api/v1/session")
                            .permitAll();
                    authorize
                            .requestMatchers(HttpMethod.DELETE, "/api/v1/session")
                            .permitAll();
                    if (environment.matchesProfiles("dev")) {
                        authorize
                                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                                .permitAll();
                    } else {
                        authorize
                                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                                .denyAll();
                    }
                    authorize.requestMatchers(SPA_MATCHER).permitAll();
                    authorize.anyRequest().authenticated();
                });
        // Local login above deliberately does not use http.formLogin(...) - see the class
        // Javadoc. OIDC does use the filter-based http.oauth2Login(...) DSL (unlike local login)
        // because there is no equivalent "call AuthenticationManager directly from a JSON
        // controller" shape for a browser-redirect authorization-code flow; this also means
        // session-fixation protection (.sessionFixation(...) above) applies to it automatically,
        // without SessionAuthenticationService's manual replication for local login.
        if (authenticationProperties.mode() != AuthenticationMode.LOCAL_AND_OIDC) {
            return http.build();
        }
        // AuthenticationProperties.isOidcConfigurationUsableWhenRequired() already failed
        // application startup if oidc() were not fully usable, so this is safe to build here
        // without re-validating: see that class's Javadoc for the fail-fast contract.
        ClientRegistrationRepository clientRegistrationRepository =
                new LazyOidcClientRegistrationRepository(authenticationProperties.oidc());
        OidcFailureResponseWriter oidcFailureResponseWriter = new OidcFailureResponseWriter(responseWriter);
        http.oauth2Login(oauth2 -> oauth2.clientRegistrationRepository(clientRegistrationRepository)
                        .authorizedClientRepository(noPersistenceOAuth2AuthorizedClientRepository)
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                        .successHandler(oidcAuthenticationSuccessHandler)
                        .failureHandler(oidcAuthenticationFailureHandler))
                // A defensive safety net for the /login/oauth2/code/oidc callback filter - see
                // OidcProviderUnavailableExceptionFilter's Javadoc for exactly which failures
                // this catches and why oidcAuthenticationFailureHandler above does not.
                .addFilterBefore(
                        new OidcProviderUnavailableExceptionFilter(oidcFailureResponseWriter),
                        OAuth2AuthorizationRequestRedirectFilter.class);
        SecurityFilterChain chain = http.build();
        // OAuth2AuthorizationRequestRedirectFilter (GET /oauth2/authorization/oidc) swallows an
        // issuer-discovery failure internally and calls response.sendError(500) by default,
        // rather than letting it propagate to the filter added above - see
        // OidcAuthorizationRequestFailureHandler's Javadoc. There is no hook for this on the
        // oauth2Login(...) DSL, so it is set directly on the filter instance http.build() already
        // constructed, via that filter's public setAuthenticationFailureHandler (Spring Security
        // 6.3+).
        chain.getFilters().stream()
                .filter(OAuth2AuthorizationRequestRedirectFilter.class::isInstance)
                .map(OAuth2AuthorizationRequestRedirectFilter.class::cast)
                .findFirst()
                .ifPresent(filter -> filter.setAuthenticationFailureHandler(
                        new OidcAuthorizationRequestFailureHandler(oidcFailureResponseWriter)));
        return chain;
    }
}

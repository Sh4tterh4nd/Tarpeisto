package io.kellermann.tarpeisto.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Beans that back the manual, JSON-API login performed by {@link SessionAuthenticationService}
 * (ADR-0001: "Login is a JSON API endpoint, not a server-rendered form login"). There is no {@code
 * http.formLogin(...)} in {@link SecurityConfiguration}: instead {@code SessionController} calls
 * {@link AuthenticationManager#authenticate(org.springframework.security.core.Authentication)}
 * directly and this class supplies the collaborators that a filter-based login would otherwise get
 * from the {@code HttpSecurity} DSL.
 */
@Configuration(proxyBeanMethods = false)
class AuthenticationBeansConfiguration {

    @Bean
    AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Default, set explicitly: this is exactly what makes an unknown username surface as the
        // same BadCredentialsException as a wrong password for a known username.
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }

    /**
     * Rotates the session identifier on successful authentication while preserving session
     * attributes, mirroring {@code SecurityConfiguration}'s {@code
     * .sessionFixation(SessionFixationConfigurer::changeSessionId)} for the filter-based flows a
     * later phase adds (OIDC). {@link SessionAuthenticationService} invokes this explicitly because
     * a manually authenticated, controller-driven login does not go through {@code
     * AbstractAuthenticationProcessingFilter}, which is what would otherwise apply it.
     */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}

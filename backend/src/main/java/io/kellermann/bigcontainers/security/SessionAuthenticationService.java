package io.kellermann.bigcontainers.security;

import io.kellermann.bigcontainers.exception.InvalidCredentialsException;
import io.kellermann.bigcontainers.service.ActivityLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Backs {@code POST /api/v1/session} (login) and {@code DELETE /api/v1/session} (logout): the
 * JSON-API session resource described by ADR-0001, not a server-rendered form login.
 *
 * <p>This replicates, by hand, what {@code AbstractAuthenticationProcessingFilter} normally does
 * for a filter-based login - authenticate, rotate the session id, then persist a fresh {@code
 * SecurityContext} - because a JSON login endpoint invokes {@link AuthenticationManager} directly
 * from a controller instead of going through that filter.
 */
@Component
public class SessionAuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final LoginRateLimiter rateLimiter;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final ActivityLogService activityLogService;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public SessionAuthenticationService(
            AuthenticationManager authenticationManager,
            LoginRateLimiter rateLimiter,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository,
            ActivityLogService activityLogService) {
        this.authenticationManager = authenticationManager;
        this.rateLimiter = rateLimiter;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.activityLogService = activityLogService;
    }

    /**
     * @throws io.kellermann.bigcontainers.exception.RateLimitExceededException if the rate-limit
     *     key for this username/client has exhausted its attempt budget
     * @throws InvalidCredentialsException for every other login failure: unknown username, no
     *     local credential, wrong password, or a disabled account. All four are deliberately
     *     indistinguishable in status, body, and (as far as {@code DaoAuthenticationProvider}'s
     *     built-in dummy-hash comparison makes practical) timing.
     */
    public BigContainersPrincipal login(
            String username, String password, HttpServletRequest request, HttpServletResponse response) {
        String normalizedUsername = username == null ? "" : username.trim();
        String key = rateLimitKey(normalizedUsername, request);
        rateLimiter.checkAllowed(key);

        Authentication authenticated;
        try {
            authenticated = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedUsername, password));
        } catch (AuthenticationException exception) {
            rateLimiter.recordFailedAttempt(key);
            throw new InvalidCredentialsException("Invalid username or password.");
        }

        BigContainersUserDetails details = (BigContainersUserDetails) authenticated.getPrincipal();
        BigContainersPrincipal principal = new BigContainersPrincipal(
                details.userId(),
                details.getUsername(),
                details.displayName(),
                details.organizationId(),
                details.role());
        Authentication sessionAuthentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));

        // Order matters: rotate the session id first (this may replace the underlying
        // HttpSession), then persist the security context into the (possibly new) session.
        sessionAuthenticationStrategy.onAuthentication(sessionAuthentication, request, response);

        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(sessionAuthentication);
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        rateLimiter.reset(key);
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "SESSION_LOGIN_SUCCEEDED",
                "USER",
                principal.userId(),
                null);
        return principal;
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication =
                securityContextHolderStrategy.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        securityContextHolderStrategy.clearContext();
    }

    /**
     * The rate-limit key is computed identically regardless of whether {@code username} belongs
     * to a real account, so the limiter itself never reveals account existence: only the number of
     * prior failed attempts for this exact (username, client-address) pair matters.
     */
    private String rateLimitKey(String normalizedUsername, HttpServletRequest request) {
        return normalizedUsername.toLowerCase(Locale.ROOT) + "|" + request.getRemoteAddr();
    }
}

package io.kellermann.tarpeisto.security;

import io.kellermann.tarpeisto.exception.RateLimitExceededException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after authentication/temporary-access denial and before MVC code parsing or repository work. */
final class AssetCodeRateLimitFilter extends OncePerRequestFilter {
    private final RequestMatcher matcher = PathPatternRequestMatcher.pathPattern("/api/v1/assets/by-code/{rawCode}");
    private final AssetCodeRateLimiter limiter;
    private final ProblemDetailSecurityResponseWriter writer;

    AssetCodeRateLimitFilter(AssetCodeRateLimiter limiter, ProblemDetailSecurityResponseWriter writer) {
        this.limiter = limiter;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) && matcher.matches(request)) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof TarpeistoPrincipal principal) {
                if (principal.temporary()) {
                    writer.handle(
                            request,
                            response,
                            new org.springframework.security.access.AccessDeniedException(
                                    "Permanent account required."));
                    return;
                }
                try {
                    limiter.admit(principal, request.getRemoteAddr());
                } catch (RateLimitExceededException exception) {
                    writer.write(
                            response,
                            HttpStatus.TOO_MANY_REQUESTS,
                            "RATE_LIMIT_EXCEEDED",
                            "Too Many Requests",
                            "Too many code lookups. Try again later.");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}

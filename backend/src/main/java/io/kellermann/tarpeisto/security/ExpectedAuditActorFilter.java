package io.kellermann.tarpeisto.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects a queued command if another browser tab has switched the shared session cookie. */
public class ExpectedAuditActorFilter extends OncePerRequestFilter {
    private final ProblemDetailSecurityResponseWriter writer;

    public ExpectedAuditActorFilter(ProblemDetailSecurityResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected = request.getHeader("X-Tarpeisto-Audit-Actor");
        if (expected != null && request.getRequestURI().startsWith("/api/v1/audits/")) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            Object identity = authentication == null ? null : authentication.getPrincipal();
            if (!(identity instanceof TarpeistoPrincipal principal)
                    || !expected.equals(principal.organizationId() + ":" + principal.userId())) {
                writer.write(
                        response,
                        HttpStatus.CONFLICT,
                        "AUDIT_ACTOR_CHANGED",
                        "Session changed",
                        "Sign in with the account that recorded this queued audit work.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

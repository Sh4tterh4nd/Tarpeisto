package io.kellermann.tarpeisto.security;

import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.service.TemporaryAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Refreshes enabled state, membership, role and volunteer grants before HTTP authorization. */
public class PrincipalRefreshFilter extends OncePerRequestFilter {
    private final TemporaryAccessService access;
    private final UserRepository users;
    private final OrganizationMembershipRepository memberships;
    private final ProblemDetailSecurityResponseWriter writer;

    public PrincipalRefreshFilter(
            TemporaryAccessService access,
            UserRepository users,
            OrganizationMembershipRepository memberships,
            ProblemDetailSecurityResponseWriter writer) {
        this.access = access;
        this.users = users;
        this.memberships = memberships;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof TarpeistoPrincipal original) {
            TarpeistoPrincipal refreshed;
            try {
                if (original.temporary()) refreshed = access.refresh(original);
                else {
                    var user = users.findById(original.userId())
                            .filter(u -> u.isEnabled())
                            .orElseThrow();
                    var membership = memberships
                            .findByOrganizationIdAndUserId(original.organizationId(), original.userId())
                            .orElseThrow();
                    refreshed = new TarpeistoPrincipal(
                            user.getId(),
                            user.getUsername(),
                            user.getDisplayName(),
                            membership.getOrganizationId(),
                            membership.getRole());
                }
            } catch (io.kellermann.tarpeisto.exception.InvalidCredentialsException
                    | java.util.NoSuchElementException invalid) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) request.getSession(false).invalidate();
                if (!credentialExchange(request) && request.getRequestURI().startsWith("/api/")) {
                    writer.write(
                            response,
                            HttpStatus.UNAUTHORIZED,
                            "SESSION_EXPIRED",
                            "Session Expired",
                            "Sign in again or request a new invitation.");
                    return;
                }
                chain.doFilter(request, response);
                return;
            }
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            refreshed,
                            null,
                            List.of(new SimpleGrantedAuthority(
                                    refreshed.temporary()
                                            ? "TEMPORARY_AUDITOR"
                                            : "ROLE_" + refreshed.role().name()))));
            if (refreshed.temporary()
                    && request.getRequestURI().startsWith("/api/")
                    && !temporaryRequestAllowed(request)) {
                writer.write(
                        response,
                        HttpStatus.FORBIDDEN,
                        "ACCESS_DENIED",
                        "Access Denied",
                        "This resource is outside your assignment.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean credentialExchange(HttpServletRequest r) {
        return (r.getRequestURI().equals("/api/v1/session") && !r.getMethod().equals("GET"))
                || (r.getRequestURI().equals("/api/v1/temporary-access/redemptions")
                        && r.getMethod().equals("POST"))
                || r.getRequestURI().equals("/api/v1/application")
                || r.getRequestURI().equals("/api/v1/setup");
    }

    private boolean temporaryRequestAllowed(HttpServletRequest r) {
        String path = r.getRequestURI();
        String method = r.getMethod();
        if (credentialExchange(r)) return true;
        if (method.equals("GET"))
            return path.equals("/api/v1/session")
                    || path.equals("/api/v1/temporary-access/tasks")
                    || path.matches("/api/v1/audits/tasks/[^/]+(?:/container)?")
                    || path.matches("/api/v1/audits/[^/]+/evidence")
                    || path.matches("/api/v1/findings/[^/]+/evidence")
                    || path.matches("/api/v1/media/[^/]+(?:/thumbnail)?")
                    || path.matches("/api/v1/asset-models/[^/]+/media/reference")
                    || path.matches("/api/v1/assets/[^/]+/media/(?:reference|layout)");
        if (method.equals("POST"))
            return path.matches("/api/v1/audits/tasks/[^/]+/start")
                    || path.matches("/api/v1/audits/[^/]+/(?:scans|move-scan-here|move-code-here|findings|complete)")
                    || path.matches("/api/v1/audits/[^/]+/scans/[^/]+/undo")
                    || path.matches("/api/v1/audits/[^/]+/consumables/[^/]+")
                    || path.matches("/api/v1/audits/[^/]+/findings/[^/]+/evidence");
        return false;
    }
}

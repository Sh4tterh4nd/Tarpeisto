package io.kellermann.tarpeisto.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forces resolution of the deferred {@link CsrfToken} on every request so the {@code XSRF-TOKEN}
 * cookie is actually written for a same-origin single-page-application client. Spring Security's
 * cookie-based CSRF repository only renders the cookie once the token is read; without this
 * filter, a browser session would not receive the cookie until some other code path happened to
 * read the token first. This is the pattern documented by Spring Security for SPA CSRF
 * integration.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            // Accessing the token value triggers the repository to render the response cookie.
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}

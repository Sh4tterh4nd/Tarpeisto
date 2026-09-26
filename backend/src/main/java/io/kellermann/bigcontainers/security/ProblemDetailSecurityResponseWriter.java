package io.kellermann.bigcontainers.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes RFC 9457 {@link ProblemDetail} bodies for both unauthenticated ({@code 401}) and
 * unauthorized ({@code 403}) security failures, keeping the API's error shape consistent whether
 * a request fails inside the Spring Security filter chain or later inside a controller (handled
 * by {@code io.kellermann.bigcontainers.exception.ApplicationExceptionHandler}).
 *
 * <p>Uses Jackson 3's {@code tools.jackson.databind.ObjectMapper}, not the classic {@code
 * com.fasterxml.jackson.databind.ObjectMapper}: Spring Boot 4.1's own {@code
 * spring-boot-starter-jackson} auto-configures the former as the primary {@code ObjectMapper}
 * bean; the latter is only present transitively (via springdoc/swagger-core) and has no bean
 * registered for it, so constructor-injecting it fails at startup.
 *
 * <p>{@link #write} is package-private (not merely a private implementation detail of the two
 * {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler} methods above) so other
 * security-filter-level failures that are neither an {@code AuthenticationException} nor an {@code
 * AccessDeniedException} - and therefore never reach {@code ExceptionTranslationFilter} - can still
 * produce the same RFC 9457 shape. See {@link OidcProviderUnavailableExceptionFilter}.
 */
@Component
public class ProblemDetailSecurityResponseWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String PROBLEM_TYPE_PREFIX = "urn:bigcontainers:problem:";

    private final ObjectMapper objectMapper;

    public ProblemDetailSecurityResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        write(
                response,
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "Authentication Required",
                "Sign in to access this resource.");
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(
                response,
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "Access Denied",
                "You do not have permission to access this resource.");
    }

    void write(HttpServletResponse response, HttpStatus status, String errorCode, String title, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(
                PROBLEM_TYPE_PREFIX + errorCode.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("errorCode", errorCode);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), problem);
    }
}

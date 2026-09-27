package io.kellermann.tarpeisto.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns (or propagates) a request correlation ID and a trace ID for every HTTP request, puts
 * both into the logging {@link MDC} for the duration of the request, and echoes the request ID
 * back on the response so a client can reference it when reporting a problem.
 *
 * <p>Runs before the Spring Security filter chain (via {@link Ordered#HIGHEST_PRECEDENCE}) so
 * authentication/authorization log entries and denied-request responses are correlated too.
 *
 * <p>Phase 0 has no distributed tracing library on the classpath. The trace ID here reuses an
 * incoming W3C {@code traceparent} header when present so a future tracing integration can adopt
 * it without changing this contract; otherwise a fresh one is generated per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_PARENT_HEADER = "traceparent";
    public static final String MDC_REQUEST_ID_KEY = "requestId";
    public static final String MDC_TRACE_ID_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        String traceId = resolveTraceId(request);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_REQUEST_ID_KEY, requestId);
        MDC.put(MDC_TRACE_ID_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID_KEY);
            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        return isUsable(incoming) ? incoming : UUID.randomUUID().toString();
    }

    private static String resolveTraceId(HttpServletRequest request) {
        String traceParent = request.getHeader(TRACE_PARENT_HEADER);
        String extracted = extractTraceId(traceParent);
        return extracted != null ? extracted : UUID.randomUUID().toString().replace("-", "");
    }

    /** Extracts the 32-hex-character trace-id field from a W3C {@code traceparent} header. */
    private static String extractTraceId(String traceParent) {
        if (!isUsable(traceParent)) {
            return null;
        }
        String[] parts = traceParent.split("-");
        if (parts.length < 2 || parts[1].length() != 32) {
            return null;
        }
        return parts[1];
    }

    private static boolean isUsable(String value) {
        return value != null && !value.isBlank();
    }
}

package uk.gov.hmcts.cp.filters.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Propagates the caller's trace identifiers into the logging context and back on the response.
 *
 * <p>Diverges from the live service in one respect: where that filter propagates {@code traceId} only
 * when the caller supplies it, this one generates a UUID when it is absent. Every error body in this
 * sandbox carries a {@code traceId}, and serving a blank one would train consumers to disregard the
 * field the live API asks them to quote in an escalation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class TracingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String APPLICATION_NAME = "applicationName";

    private final String applicationName;

    public TracingFilter(@Value("${spring.application.name}") final String applicationName) {
        super();
        this.applicationName = applicationName;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        MDC.put(APPLICATION_NAME, applicationName);

        final String traceId = sanitize(request.getHeader(TRACE_ID));
        final String resolvedTraceId = traceId == null ? UUID.randomUUID().toString() : traceId;
        MDC.put(TRACE_ID, resolvedTraceId);
        response.setHeader(TRACE_ID, resolvedTraceId);

        final String spanId = sanitize(request.getHeader(SPAN_ID));
        if (spanId != null) {
            MDC.put(SPAN_ID, spanId);
            response.setHeader(SPAN_ID, spanId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
            MDC.remove(SPAN_ID);
            MDC.remove(APPLICATION_NAME);
        }
    }

    /**
     * Drops CR/LF so a caller-supplied header cannot forge log records (CWE-117), and treats a blank
     * header as absent. The value is echoed on the response, so it must not carry header delimiters.
     */
    private static String sanitize(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}

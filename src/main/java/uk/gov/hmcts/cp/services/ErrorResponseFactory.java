package uk.gov.hmcts.cp.services;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.openapi.model.ErrorResponse;

import java.util.Map;

/**
 * Builds the error envelope, stamping each one with the current trace id.
 *
 * <p>The canned error bodies on disk carry a placeholder {@code traceId} so they are readable as
 * files; serving that placeholder would make every error in this sandbox untraceable and would train
 * consumers to ignore a field the live API expects them to quote in escalations. Each response
 * therefore gets the request's real trace id substituted here.
 */
@Service
public class ErrorResponseFactory {

    /**
     * Published by {@link uk.gov.hmcts.cp.filters.tracing.TracingFilter} on every request.
     */
    private static final String MDC_TRACE_ID = "traceId";

    private final ClockService clockService;

    public ErrorResponseFactory(final ClockService clockService) {
        this.clockService = clockService;
    }

    /** Re-stamps a canned error body with the live traceId and timestamp. */
    public ErrorResponse withCurrentTrace(final ErrorResponse template) {
        return ErrorResponse.builder()
                .error(template == null ? "internal_server_error" : template.getError())
                .message(template == null ? "Unexpected error." : template.getMessage())
                .details(template == null ? Map.of() : template.getDetails())
                .timestamp(clockService.now())
                .traceId(currentTraceId())
                .build();
    }

    public ErrorResponse notFound(final String message) {
        return ErrorResponse.builder()
                .error("not_found")
                .message(message)
                .details(Map.of())
                .timestamp(clockService.now())
                .traceId(currentTraceId())
                .build();
    }

    public ErrorResponse create(final String error, final String message) {
        return ErrorResponse.builder()
                .error(error)
                .message(message)
                .details(Map.of())
                .timestamp(clockService.now())
                .traceId(currentTraceId())
                .build();
    }

    private static String currentTraceId() {
        final String traceId = MDC.get(MDC_TRACE_ID);
        return traceId == null ? "unavailable" : traceId;
    }
}

package uk.gov.hmcts.cp.filters;

import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.gov.hmcts.cp.auth.AdminTokenValidator;
import uk.gov.hmcts.cp.auth.AuthorizationPolicy;
import uk.gov.hmcts.cp.auth.TokenValidationException;
import uk.gov.hmcts.cp.auth.ValidatedCaller;

import java.io.IOException;

/**
 * Guards ingest and publish with a real Entra token.
 *
 * <p>Runs <b>before</b> {@link ClientIdResolutionFilter} (order {@code +4} against its {@code +5}),
 * which is what makes it safe for {@link AuthorizationPolicy} to hand {@code /admin/**} over to this
 * filter. By the time the demo filter's {@code shouldNotFilter} consults the policy, an admin
 * request has already been accepted or rejected here.
 *
 * <p>The distinction between the two realms matters: a 401 from this filter means "your corporate
 * Entra token is not valid", while a 401 from the demo filter means "your sandbox demo token is not
 * valid". They are different credentials for different audiences, so the rejection names which.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
@Slf4j
public class AdminAuthFilter extends OncePerRequestFilter {

    /** The validated admin caller, for controllers that need the reviewer's identity. */
    public static final String CALLER_ATTRIBUTE = "adminCaller";

    private static final String MDC_ADMIN_OID = "adminOid";

    private final AdminTokenValidator adminTokenValidator;

    public AdminAuthFilter(final AdminTokenValidator adminTokenValidator) {
        this.adminTokenValidator = adminTokenValidator;
    }

    @Override
    protected boolean shouldNotFilter(@Nonnull final HttpServletRequest request) {
        // Preflight carries no Authorization header by specification - same reasoning as the demo
        // filter. The actual request that follows is still authenticated.
        return CorsUtils.isPreFlightRequest(request)
                || !AuthorizationPolicy.isAdminPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@Nonnull final HttpServletRequest request,
                                    @Nonnull final HttpServletResponse response,
                                    @Nonnull final FilterChain filterChain)
            throws ServletException, IOException {

        if (!adminTokenValidator.isEnabled()) {
            // Closed, not open. An unconfigured admin realm must not become an unguarded one.
            log.warn("ADMIN REJECT: {} {} - admin realm disabled",
                    sanitize(request.getMethod()), sanitize(request.getRequestURI()));
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "admin_disabled",
                    "The recording admin API is not configured in this environment.");
            return;
        }

        final ValidatedCaller caller;
        try {
            caller = adminTokenValidator.validate(request.getHeader(HttpHeaders.AUTHORIZATION));
        } catch (final TokenValidationException ex) {
            final TokenValidationException.Reason reason = ex.getReason();
            log.warn("ADMIN REJECT: {} {} reason:{}",
                    sanitize(request.getMethod()), sanitize(request.getRequestURI()), reason);
            final int status = reason.isAuthenticationFailure()
                    ? HttpServletResponse.SC_UNAUTHORIZED
                    : HttpServletResponse.SC_FORBIDDEN;
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    "Bearer realm=\"admin\", error=\"" + reason.getErrorCode() + "\"");
            writeError(response, status, reason.getErrorCode(), reason.getDescription());
            return;
        }

        request.setAttribute(CALLER_ATTRIBUTE, caller);
        MDC.put(MDC_ADMIN_OID, caller.clientId().toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_ADMIN_OID);
        }
    }

    private static void writeError(final HttpServletResponse response, final int status,
                                   final String error, final String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }

    /** Strips CR/LF so a request path cannot forge log records (CWE-117). */
    private static String sanitize(final String value) {
        return value == null ? null : value.replace('\r', ' ').replace('\n', ' ');
    }
}

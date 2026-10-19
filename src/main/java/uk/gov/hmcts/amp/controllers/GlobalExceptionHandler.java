package uk.gov.hmcts.amp.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uk.gov.hmcts.amp.auth.TokenValidationException;
import uk.gov.hmcts.cp.openapi.model.ErrorResponse;
import uk.gov.hmcts.amp.scenarios.ContractViolationException;
import uk.gov.hmcts.amp.scenarios.ScenarioFailureException;
import uk.gov.hmcts.amp.services.ErrorResponseFactory;

/**
 * Maps exceptions onto the contract's error envelope.
 *
 * <p>Note this handler never sees a 401 or 403 from token validation: those are written directly by
 * {@link uk.gov.hmcts.amp.filters.ClientIdResolutionFilter}, which runs in the servlet filter chain
 * ahead of the dispatcher — the same ordering as the live service.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private final ErrorResponseFactory errorResponseFactory;

    public GlobalExceptionHandler(final ErrorResponseFactory errorResponseFactory) {
        this.errorResponseFactory = errorResponseFactory;
    }

    /** A scenario that is defined to answer with a non-2xx status. */
    @ExceptionHandler(ScenarioFailureException.class)
    public ResponseEntity<ErrorResponse> handleScenarioFailure(final ScenarioFailureException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ex.getErrorResponse());
    }

    /**
     * A capture that does not fit the published contract.
     *
     * <p>Defensive only. Fixtures are checked at build time and again when they are seeded, so a
     * request should not reach this. If it does, the message names the offending field.
     */
    @ExceptionHandler(ContractViolationException.class)
    public ResponseEntity<ErrorResponse> handleContractViolation(final ContractViolationException ex) {
        log.error("A fixture does not match the contract and reached a request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(
                errorResponseFactory.create("contract_violation", ex.getMessage()));
    }

    /**
     * A path this service does not serve.
     *
     * <p>Handled explicitly because the catch-all below would otherwise turn it into a 500: Spring
     * raises {@link NoResourceFoundException} for an unmapped path, and a {@code @RestControllerAdvice}
     * that handles {@code Exception} intercepts it before the default 404 is produced. A consumer
     * who mistypes a URL should be told the path does not exist, not that the sandbox is broken.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(final NoResourceFoundException ex) {
        return ResponseEntity.status(404).body(
                errorResponseFactory.create("not_found", "No such path: " + ex.getResourcePath()));
    }

    /**
     * A caller whose token was valid but who lacks the role for this operation - thrown by
     * {@code AuthorizationPolicy} once the token itself has verified.
     */
    @ExceptionHandler(TokenValidationException.class)
    public ResponseEntity<ErrorResponse> handleTokenValidation(final TokenValidationException ex) {
        final int status = ex.getReason().isAuthenticationFailure() ? 401 : 403;
        return ResponseEntity.status(status).body(
                errorResponseFactory.create(ex.getReason().getErrorCode(), ex.getReason().getDescription()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(final Exception ex) {
        // Log the cause, return none of it: an internal class name or message is not a consumer's
        // business, even in a sandbox.
        log.error("Unhandled exception serving try-it-now request", ex);
        return ResponseEntity.status(500).body(errorResponseFactory.create(
                "internal_server_error", "Unexpected error. Quote the traceId when reporting this."));
    }
}

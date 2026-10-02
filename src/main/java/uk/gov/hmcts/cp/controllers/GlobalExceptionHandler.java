package uk.gov.hmcts.cp.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uk.gov.hmcts.cp.auth.TokenValidationException;
import uk.gov.hmcts.cp.openapi.model.ErrorResponse;
import uk.gov.hmcts.cp.recordings.RecordingNotFoundException;
import uk.gov.hmcts.cp.scenarios.ContractViolationException;
import uk.gov.hmcts.cp.scenarios.ScenarioFailureException;
import uk.gov.hmcts.cp.services.ErrorResponseFactory;

/**
 * Maps exceptions onto the contract's error envelope.
 *
 * <p>Note this handler never sees a 401 or 403 from token validation: those are written directly by
 * {@link uk.gov.hmcts.cp.filters.ClientIdResolutionFilter}, which runs in the servlet filter chain
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
     * <p>The message is returned to the caller because it names the offending field, and the caller
     * is the one holding the payload being described. A rejection that only said "invalid" would
     * leave whoever ran the capture with nowhere to start.
     */
    @ExceptionHandler(ContractViolationException.class)
    public ResponseEntity<ErrorResponse> handleContractViolation(final ContractViolationException ex) {
        log.info("Rejected a recording that does not match the contract: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(
                errorResponseFactory.create("contract_violation", ex.getMessage()));
    }

    @ExceptionHandler(RecordingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRecordingNotFound(final RecordingNotFoundException ex) {
        return ResponseEntity.status(404).body(errorResponseFactory.create("not_found", ex.getMessage()));
    }

    /**
     * An admin caller whose token was valid but who lacks the role for this operation. Thrown from
     * the controller rather than the filter, because the required role differs per operation.
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

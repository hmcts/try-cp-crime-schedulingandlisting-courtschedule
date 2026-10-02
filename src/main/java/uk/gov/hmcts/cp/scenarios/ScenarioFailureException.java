package uk.gov.hmcts.cp.scenarios;

import lombok.Getter;
import uk.gov.hmcts.cp.openapi.model.ErrorResponse;

/**
 * Raised to serve a scenario whose outcome is a non-2xx response.
 *
 * <p>The generated {@code CourtScheduleApi} method returns {@code ResponseEntity<CourtScheduleResponse>},
 * so an error body cannot be returned through it directly. Throwing here and mapping in
 * {@link uk.gov.hmcts.cp.controllers.GlobalExceptionHandler} keeps the controller's signature exactly
 * as the contract generated it, which is the same shape the real service uses.
 */
@Getter
public class ScenarioFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorResponse errorResponse;
    private final int status;

    public ScenarioFailureException(final int status, final ErrorResponse errorResponse) {
        super("scenario responds " + status);
        this.status = status;
        this.errorResponse = errorResponse;
    }
}

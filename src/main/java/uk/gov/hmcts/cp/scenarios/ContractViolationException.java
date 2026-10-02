package uk.gov.hmcts.cp.scenarios;

/**
 * Thrown when a body does not match the published contract.
 *
 * <p>The message names the offending field, because a rejection that only says "invalid" leaves the
 * person who captured the response with nowhere to start. It is safe to return to the caller: the
 * caller supplied the body being described.
 */
public class ContractViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ContractViolationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

package uk.gov.hmcts.cp.auth;

import lombok.Getter;

/**
 * Thrown when an Entra access token fails validation.
 *
 * <p>Carries a coarse {@link Reason} rather than a detailed message: the reason is safe to log and
 * to expose in {@code WWW-Authenticate}, whereas token contents are not. The raw token is never
 * held by this exception.
 *
 * <p><b>Checked deliberately.</b> A rejected token is an expected outcome that a caller must turn
 * into a response, not a bug. Declaring it makes that obligation part of every signature on the
 * path, so a new call site cannot forget to handle it and quietly return 500 in place of 401.
 */
@Getter
public class TokenValidationException extends Exception {

    private static final long serialVersionUID = 1L;

    private static final String INVALID_TOKEN = "invalid_token";
    private static final String INSUFFICIENT_SCOPE = "insufficient_scope";

    /**
     * Why validation failed. {@link #authenticationFailure} decides 401 (we do not know who you
     * are) versus 403 (we know who you are, and you may not do this).
     */
    public enum Reason {
        MISSING_AUTHORIZATION_HEADER(INVALID_TOKEN, "missing authorization header", true),
        UNSUPPORTED_SCHEME(INVALID_TOKEN, "authorization scheme must be Bearer", true),
        EMPTY_TOKEN(INVALID_TOKEN, "empty bearer token", true),
        MALFORMED_TOKEN(INVALID_TOKEN, "malformed token", true),
        INVALID_SIGNATURE(INVALID_TOKEN, "invalid signature", true),
        INVALID_CLAIMS(INVALID_TOKEN, "token claims failed validation", true),
        DELEGATED_TOKEN(INSUFFICIENT_SCOPE, "app-only token required", false),
        MISSING_CLIENT_ID(INVALID_TOKEN, "token has no azp claim", true),
        MALFORMED_CLIENT_ID(INVALID_TOKEN, "azp claim is not a uuid", true),
        MISSING_ROLE(INSUFFICIENT_SCOPE, "token carries no application role", false),
        INSUFFICIENT_ROLE(INSUFFICIENT_SCOPE, "token lacks the role required for this operation", false);

        private final String errorCode;
        private final String description;
        private final boolean authenticationFailure;

        Reason(final String errorCode, final String description, final boolean authenticationFailure) {
            this.errorCode = errorCode;
            this.description = description;
            this.authenticationFailure = authenticationFailure;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getDescription() {
            return description;
        }

        public boolean isAuthenticationFailure() {
            return authenticationFailure;
        }
    }

    private final Reason reason;

    public TokenValidationException(final Reason reason) {
        super(reason.getDescription());
        this.reason = reason;
    }

    public TokenValidationException(final Reason reason, final Throwable cause) {
        super(reason.getDescription(), cause);
        this.reason = reason;
    }
}

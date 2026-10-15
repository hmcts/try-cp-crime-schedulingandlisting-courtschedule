package uk.gov.hmcts.cp.auth;

import lombok.extern.slf4j.Slf4j;

/**
 * Validates admin-realm tokens.
 *
 * <p>Wraps an {@link EntraTokenValidator} rather than being one, so there is never a second bean of
 * that type on the context — the demo realm's validator stays the only one, and the copied filter
 * that injects it keeps working untouched.
 *
 * <p>When the admin realm is disabled there is no delegate, and every call is refused. The absence
 * of configuration closes the door rather than opening it.
 */
@Slf4j
public class AdminTokenValidator {

    private final EntraTokenValidator delegate;
    private final AdminAuthProperties properties;

    public AdminTokenValidator(final EntraTokenValidator delegate, final AdminAuthProperties properties) {
        this.delegate = delegate;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return delegate != null && !properties.isDisabled();
    }

    /**
     * @throws TokenValidationException if the token is missing, invalid, or carries no admin role
     * @throws IllegalStateException if called while disabled — callers must check {@link #isEnabled()}
     */
    public ValidatedCaller validate(final String authorizationHeader) throws TokenValidationException {
        if (!isEnabled()) {
            throw new IllegalStateException("Admin realm is disabled; check isEnabled() first");
        }
        return delegate.validate(authorizationHeader);
    }

    /** Ingest requires the write role. */
    public void assertMayRecord(final ValidatedCaller caller) throws TokenValidationException {
        assertRole(caller, properties.getWriteRole());
    }

    /** Publishing requires its own role, so capturing does not imply approving. */
    public void assertMayPublish(final ValidatedCaller caller) throws TokenValidationException {
        assertRole(caller, properties.getPublishRole());
    }

    private static void assertRole(final ValidatedCaller caller, final String required)
            throws TokenValidationException {
        if (!caller.hasRole(required)) {
            throw new TokenValidationException(TokenValidationException.Reason.INSUFFICIENT_ROLE);
        }
    }
}

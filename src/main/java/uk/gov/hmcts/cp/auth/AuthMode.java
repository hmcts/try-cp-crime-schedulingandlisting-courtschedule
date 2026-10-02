package uk.gov.hmcts.cp.auth;

/**
 * Rollout state for Entra access token validation.
 */
public enum AuthMode {

    /**
     * No validation. Client identity is taken from the unverified {@code azp} claim. Must be
     * rejected at startup outside local/dev by {@link EntraAuthProperties}.
     */
    OFF,

    /**
     * Tokens are fully validated and every failure is logged and counted, but no request is
     * rejected; identity still falls back to the unverified {@code azp} so behaviour is unchanged.
     *
     * <p><b>This provides no protection.</b> It is a diagnostic step for finding broken clients,
     * not remediation, and is rejected at startup outside local/dev.
     */
    OBSERVE,

    /** Tokens are validated and invalid requests are rejected. The only mode permitted in a deployed environment. */
    ENFORCE
}

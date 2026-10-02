package uk.gov.hmcts.cp.auth;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Configuration for Entra access token validation.
 *
 * <p>Fails startup rather than degrading: a blank audience must never mean "accept any audience".
 * {@code auth.mode} itself is trusted as injected by the deployment (Kubernetes) — this service
 * does not second-guess it against which environment it is running in.
 */
@Getter
@Service
@Slf4j
public class EntraAuthProperties {

    private static final int MAX_CLOCK_SKEW_SECONDS = 300;

    /** Whether a failing token is rejected. Not a token value — see {@link AuthMode}. */
    private final AuthMode mode;

    /**
     * The tenant that <b>issues</b> the tokens — matched against the token's {@code tid} claim, and
     * the value {@link #issuer} and {@link #jwksUri} are derived from when those are left blank.
     *
     * <p>Where the service is hosted in a different Azure tenant from the one issuing tokens, this is
     * the <b>issuing</b> tenant. The hosting tenant's directory id is the wrong value and would
     * reject every token.
     */
    private final String tenantId;

    /**
     * This API's own application id, matched against the token's {@code aud} claim.
     *
     * <p>The single highest-value check: {@code aud} is the only thing that rejects a genuine,
     * correctly signed token minted for a <i>different</i> resource — a Microsoft Graph token, or a
     * sibling API's. v2.0 tokens carry the bare GUID here, not {@code api://<...>}.
     */
    private final String audience;

    /**
     * Matched against the token's {@code iss} claim, exactly — never by prefix, which would admit
     * {@code .../v2.0.attacker.example}. Derived from {@link #tenantId} when not set.
     */
    private final String issuer;

    /**
     * Where the public signing keys are fetched from. The token's {@code kid} header selects one of
     * them; nothing in the token influences this URL, or a caller could nominate the key that
     * verifies it. Derived from {@link #tenantId} when not set.
     */
    private final String jwksUri;

    /**
     * Tolerance applied to the token's {@code exp} and {@code nbf} claims, absorbing clock drift
     * between Entra and this pod. Capped at {@value #MAX_CLOCK_SKEW_SECONDS} seconds, since a large
     * enough value turns {@code exp} into a no-op.
     */
    private final int clockSkewSeconds;

    /**
     * How long the fetched JWKS is cached, so verification costs no outbound call per request. Not a
     * token value. An unknown {@code kid} triggers a rate-limited refresh regardless, which is how
     * Entra's key rotation is absorbed without a redeploy.
     */
    private final long jwksCacheTtlSeconds;

    public EntraAuthProperties(
            @Value("${auth.mode:ENFORCE}") final AuthMode mode,
            @Value("${auth.tenant-id:}") final String tenantId,
            @Value("${auth.audience:}") final String audience,
            @Value("${auth.issuer:}") final String issuer,
            @Value("${auth.jwks-uri:}") final String jwksUri,
            @Value("${auth.clock-skew-seconds:60}") final int clockSkewSeconds,
            @Value("${auth.jwks-cache-ttl-seconds:600}") final long jwksCacheTtlSeconds) {

        this.mode = mode;
        this.tenantId = tenantId;
        this.audience = audience;
        this.clockSkewSeconds = Math.min(clockSkewSeconds, MAX_CLOCK_SKEW_SECONDS);
        this.jwksCacheTtlSeconds = jwksCacheTtlSeconds;
        this.issuer = issuer.isBlank() ? defaultIssuer(tenantId) : issuer;
        this.jwksUri = jwksUri.isBlank() ? defaultJwksUri(tenantId) : jwksUri;

        validate();

        log.info("Entra auth initialised mode:{} issuer:{} audience:{} clockSkewSeconds:{}",
                this.mode, this.issuer, this.audience, this.clockSkewSeconds);
        if (this.mode != AuthMode.ENFORCE) {
            log.warn("SECURITY: Entra token enforcement is {} — client identity is taken from an "
                    + "UNVERIFIED token. This must not be used in a deployed environment.", this.mode);
        }
    }

    private void validate() {
        if (mode == AuthMode.OFF) {
            return;
        }
        if (tenantId.isBlank()) {
            throw new IllegalStateException("auth.tenant-id must be set when auth.mode=" + mode);
        }
        if (audience.isBlank()) {
            throw new IllegalStateException(
                    "auth.audience must be set when auth.mode=" + mode
                            + ". A blank audience would accept tokens minted for any resource, "
                            + "including Microsoft Graph.");
        }
    }

    private static String defaultIssuer(final String tenantId) {
        return "https://login.microsoftonline.com/" + tenantId + "/v2.0";
    }

    private static String defaultJwksUri(final String tenantId) {
        return "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys";
    }
}

package uk.gov.hmcts.amp.config;

/**
 * Where the keys that verify an access token come from — and therefore who issued the token.
 *
 * <p>This is <b>not</b> a relaxation of {@link uk.gov.hmcts.amp.auth.AuthMode}. Validation is
 * identical either way: the same {@link uk.gov.hmcts.amp.auth.EntraTokenValidator}, the same
 * signature, issuer, audience, tenant, {@code ver}, {@code exp}, app-only and role checks. Only the
 * key source and the issuer identifiers differ.
 *
 * <p>The two are mutually exclusive by construction. {@link #ENTRA} removes the token endpoint, the
 * JWKS endpoint and the demo signing key from the context altogether, so the service cannot mint a
 * token it would then have to reject — which is what made a single shared {@code auth.*} block
 * dangerous before this existed.
 */
public enum TokenKeySource {

    /**
     * The sandbox is its own issuer: it mints tokens at {@code /oauth2/v2.0/token} and verifies them
     * against a key it holds in memory. No egress, no Entra, no app registration.
     *
     * <p>Local development, and any deployed environment that has no app registration yet. Tokens
     * issued here are worthless against a real HMCTS API, because {@code iss}/{@code aud}/{@code tid}
     * are this service's own invented identifiers.
     */
    SELF_ISSUED,

    /**
     * Tokens are real Microsoft Entra app-only access tokens, verified against the tenant's published
     * JWKS over HTTPS. The caller obtains them from {@code login.microsoftonline.com}, not from here.
     *
     * <p>The default, so an environment that was never configured fails closed at startup rather than
     * quietly accepting tokens this service minted itself.
     */
    ENTRA
}

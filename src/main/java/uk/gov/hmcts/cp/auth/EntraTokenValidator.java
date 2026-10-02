package uk.gov.hmcts.cp.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import lombok.extern.slf4j.Slf4j;

import java.text.ParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static uk.gov.hmcts.cp.auth.TokenValidationException.Reason.DELEGATED_TOKEN;
import static uk.gov.hmcts.cp.auth.TokenValidationException.Reason.EMPTY_TOKEN;
import static uk.gov.hmcts.cp.auth.TokenValidationException.Reason.INVALID_CLAIMS;
import static uk.gov.hmcts.cp.auth.TokenValidationException.Reason.INVALID_SIGNATURE;
import static uk.gov.hmcts.cp.auth.TokenValidationException.Reason.MALFORMED_CLIENT_ID;
import static uk.gov.hmcts.cp.auth.TokenValidationException.Reason.MALFORMED_TOKEN;
import static uk.gov.hmcts.cp.auth.TokenValidationException.Reason.MISSING_AUTHORIZATION_HEADER;
import static uk.gov.hmcts.cp.auth.TokenValidationException.Reason.MISSING_CLIENT_ID;
import static uk.gov.hmcts.cp.auth.TokenValidationException.Reason.MISSING_ROLE;
import static uk.gov.hmcts.cp.auth.TokenValidationException.Reason.UNSUPPORTED_SCHEME;

/**
 * Validates a Microsoft Entra app-only (client credentials) access token.
 *
 * <p>Everything cryptographic is delegated to Nimbus. In particular the key selector is constructed
 * for exactly one algorithm, which makes {@code alg: none}, RSA-public-key-as-HMAC-secret confusion
 * and token-supplied key material ({@code jku}/{@code jwk}/{@code x5u}) structurally impossible
 * rather than separately defended — the selector only ever consults the configured JWKS.
 *
 * <p>Note that Entra's JWKS entries carry no {@code alg} field, so the permitted algorithm cannot be
 * inferred from the key set and must be pinned here.
 *
 * <p>Deliberately <b>not</b> checked, because each lacks a threat model in this context: in-app TLS
 * enforcement, token size limits, duplicate JSON keys, {@code iat} max age, {@code typ}, and replay
 * or nonce tracking.
 */
@Slf4j
public class EntraTokenValidator {

    private static final String BEARER_PREFIX = "bearer ";
    private static final String CLAIM_AZP = "azp";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_OID = "oid";
    private static final String CLAIM_SCP = "scp";
    private static final String CLAIM_TENANT_ID = "tid";
    private static final String CLAIM_TOKEN_VERSION = "ver";
    private static final String CLAIM_EXPIRY = "exp";
    private static final String TOKEN_VERSION_V2 = "2.0";

    private final DefaultJWTProcessor<SecurityContext> processor;

    public EntraTokenValidator(final EntraAuthProperties properties,
                               final JWKSource<SecurityContext> jwkSource) {
        this.processor = buildProcessor(properties, jwkSource);
    }

    private static DefaultJWTProcessor<SecurityContext> buildProcessor(
            final EntraAuthProperties properties, final JWKSource<SecurityContext> jwkSource) {

        final DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();

        // Single-algorithm selector: satisfies the alg allowlist, alg:none and alg-confusion
        // rejection, and header-supplied-key rejection in one place.
        jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));

        final JWTClaimsSet exactMatch = new JWTClaimsSet.Builder()
                .issuer(properties.getIssuer())
                .claim(CLAIM_TENANT_ID, properties.getTenantId())
                .claim(CLAIM_TOKEN_VERSION, TOKEN_VERSION_V2)
                .build();

        // These must tolerate contains(null): Nimbus probes acceptedAudience for a null element to
        // decide whether "aud" is mandatory, and Set.of(...) throws NPE on contains(null) rather
        // than returning false.
        final DefaultJWTClaimsVerifier<SecurityContext> verifier = new DefaultJWTClaimsVerifier<>(
                new HashSet<>(Set.of(properties.getAudience())),
                exactMatch,
                new HashSet<>(Set.of(CLAIM_EXPIRY)),
                // A delegated (user) token carries scp. Prohibiting it enforces app-only without
                // depending on idtyp, which Entra omits unless explicitly enabled as an optional claim.
                new HashSet<>(Set.of(CLAIM_SCP)));
        verifier.setMaxClockSkew(properties.getClockSkewSeconds());
        jwtProcessor.setJWTClaimsSetVerifier(verifier);

        return jwtProcessor;
    }

    /** Validates the {@code Authorization} header and returns the verified caller. */
    public ValidatedCaller validate(final String authorizationHeader) throws TokenValidationException {
        final String token = extractBearerToken(authorizationHeader);
        final JWTClaimsSet claims = verify(token);
        return toCaller(claims);
    }

    /**
     * Reads {@code azp} from a token <b>without verifying it</b>, for {@link AuthMode#OFF} and
     * {@link AuthMode#OBSERVE} only, where the service must keep working while broken clients are
     * identified.
     *
     * <p>Every claim here is attacker-controlled. Never call this on an enforcement path.
     */
    public UUID unverifiedClientIdForNonEnforcingModesOnly(final String authorizationHeader)
            throws TokenValidationException {
        final String token = extractBearerToken(authorizationHeader);
        try {
            return toClientId(SignedJWT.parse(token).getJWTClaimsSet());
        } catch (final ParseException e) {
            throw new TokenValidationException(MALFORMED_TOKEN, e);
        }
    }

    private static String extractBearerToken(final String authorizationHeader) throws TokenValidationException {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new TokenValidationException(MISSING_AUTHORIZATION_HEADER);
        }
        // RFC 6750 s2.1 — the scheme is case-insensitive.
        if (!authorizationHeader.toLowerCase(Locale.ROOT).startsWith(BEARER_PREFIX)) {
            throw new TokenValidationException(UNSUPPORTED_SCHEME);
        }
        final String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new TokenValidationException(EMPTY_TOKEN);
        }
        return token;
    }

    private JWTClaimsSet verify(final String token) throws TokenValidationException {
        final SignedJWT signedJwt;
        try {
            signedJwt = SignedJWT.parse(token);
        } catch (final ParseException e) {
            throw new TokenValidationException(MALFORMED_TOKEN, e);
        }
        try {
            return processor.process(signedJwt, null);
        } catch (final BadJWTException e) {
            throw new TokenValidationException(INVALID_CLAIMS, e);
        } catch (final BadJOSEException | JOSEException e) {
            throw new TokenValidationException(INVALID_SIGNATURE, e);
        }
    }

    private static ValidatedCaller toCaller(final JWTClaimsSet claims) throws TokenValidationException {
        assertAppOnly(claims);
        final List<String> roles = readRoles(claims);
        if (roles.isEmpty()) {
            throw new TokenValidationException(MISSING_ROLE);
        }
        return new ValidatedCaller(toClientId(claims), roles, true);
    }

    /**
     * For an app-only token Entra sets {@code sub} to the service principal object id, so it equals
     * {@code oid}. For a delegated token {@code sub} identifies the user and the two differ.
     */
    private static void assertAppOnly(final JWTClaimsSet claims) throws TokenValidationException {
        final String subject = claims.getSubject();
        final String objectId = claims.getClaim(CLAIM_OID) == null ? null : claims.getClaim(CLAIM_OID).toString();
        if (subject == null || !subject.equals(objectId)) {
            throw new TokenValidationException(DELEGATED_TOKEN);
        }
    }

    private static List<String> readRoles(final JWTClaimsSet claims) throws TokenValidationException {
        try {
            final List<String> roles = claims.getStringListClaim(CLAIM_ROLES);
            return roles == null ? List.of() : roles;
        } catch (final ParseException e) {
            throw new TokenValidationException(INVALID_CLAIMS, e);
        }
    }

    /**
     * The caller's identity is {@code azp} — the client application's id, which is the tenancy key.
     * It is not {@code oid}/{@code sub}, which is the service principal object id for that
     * application in the tenant.
     */
    private static UUID toClientId(final JWTClaimsSet claims) throws TokenValidationException {
        final String azp;
        try {
            azp = claims.getStringClaim(CLAIM_AZP);
        } catch (final ParseException e) {
            throw new TokenValidationException(MALFORMED_CLIENT_ID, e);
        }
        if (azp == null || azp.isBlank()) {
            throw new TokenValidationException(MISSING_CLIENT_ID);
        }
        try {
            return UUID.fromString(azp);
        } catch (final IllegalArgumentException e) {
            throw new TokenValidationException(MALFORMED_CLIENT_ID, e);
        }
    }
}

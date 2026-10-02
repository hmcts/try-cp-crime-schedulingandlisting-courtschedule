package uk.gov.hmcts.cp.demo;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.auth.EntraAuthProperties;
import uk.gov.hmcts.cp.services.ClockService;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints the demo access tokens this sandbox issues and then verifies.
 *
 * <p>The claim set below is not decorative — it is dictated, claim for claim, by the copied
 * {@link uk.gov.hmcts.cp.auth.EntraTokenValidator}, which is byte-identical to the one guarding the
 * live API. A token missing any of these, or carrying {@code scp}, is rejected by the same code path
 * that would reject it in production. That is the point: the sandbox does not simulate the auth
 * contract, it runs it.
 *
 * <p>Every identifier here ({@code iss}, {@code aud}, {@code tid}) belongs to this sandbox, so a demo
 * token verifies nowhere else.
 */
@Service
public class DemoTokenMinter {

    private static final String CLAIM_AZP = "azp";
    private static final String CLAIM_OID = "oid";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_TENANT_ID = "tid";
    private static final String CLAIM_TOKEN_VERSION = "ver";
    private static final String TOKEN_VERSION_V2 = "2.0";

    private final DemoSigningKey signingKey;
    private final EntraAuthProperties authProperties;
    private final ClockService clockService;
    private final Duration tokenTtl;

    public DemoTokenMinter(final DemoSigningKey signingKey,
                           final EntraAuthProperties authProperties,
                           final ClockService clockService,
                           @Value("${demo.token-ttl-seconds:3600}") final long tokenTtlSeconds) {
        this.signingKey = signingKey;
        this.authProperties = authProperties;
        this.clockService = clockService;
        this.tokenTtl = Duration.ofSeconds(tokenTtlSeconds);
    }

    public long ttlSeconds() {
        return tokenTtl.toSeconds();
    }

    /** Signs an app-only access token for the given demo client. */
    public String mint(final DemoClient client) {
        final Instant now = clockService.now();

        // sub must equal oid: that is how the validator's assertAppOnly distinguishes an application
        // token from a delegated (user) one, and Entra sets them equal for client-credentials grants.
        final String objectId = deterministicObjectId(client.clientId()).toString();

        final JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(authProperties.getIssuer())
                .audience(authProperties.getAudience())
                .subject(objectId)
                .claim(CLAIM_OID, objectId)
                .claim(CLAIM_AZP, client.clientId().toString())
                .claim(CLAIM_TENANT_ID, authProperties.getTenantId())
                .claim(CLAIM_TOKEN_VERSION, TOKEN_VERSION_V2)
                .claim(CLAIM_ROLES, client.roles())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plus(tokenTtl)))
                .jwtID(UUID.randomUUID().toString())
                // Deliberately NO "scp" claim. Its presence marks a delegated token and the validator
                // prohibits it outright, which is how app-only access is enforced without relying on
                // "idtyp" (an optional claim Entra omits by default).
                .build();

        final SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.privateKey().getKeyID()).build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(signingKey.privateKey()));
        } catch (final JOSEException e) {
            throw new IllegalStateException("Could not sign the demo access token", e);
        }
        return jwt.serialize();
    }

    /**
     * A stable service-principal object id per demo client, so repeated token requests present a
     * consistent identity rather than a new one each time.
     */
    private static UUID deterministicObjectId(final UUID clientId) {
        return UUID.nameUUIDFromBytes(("oid:" + clientId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

package uk.gov.hmcts.cp.recordings;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints admin-realm tokens for tests, against a key held only in the test JVM.
 *
 * <p>The admin realm normally verifies against a real tenant's published keys. Rather than weaken
 * it for tests, the tests swap the key source and leave {@code ENFORCE} in place — the same
 * technique the service itself uses for its demo realm. Every claim the validator requires is
 * produced here, so a change to those requirements fails these tests rather than passing silently.
 */
public final class AdminTokens {

    public static final String TENANT_ID = "00000000-0000-0000-0000-0000000000bb";
    public static final String AUDIENCE = "api://try-it-now-admin";
    public static final String ISSUER = "https://admin.test.internal/" + TENANT_ID + "/v2.0";

    public static final String WRITE_ROLE = "app.recordings.write";
    public static final String PUBLISH_ROLE = "app.recordings.publish";

    public static final RSAKey KEY = generate();

    private AdminTokens() {
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("admin-test-key").keyUse(KeyUse.SIGNATURE).generate();
        } catch (final JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String token(final UUID clientId, final List<String> roles) {
        final Instant now = Instant.now();
        final String oid = UUID.nameUUIDFromBytes(("oid:" + clientId).getBytes()).toString();

        final JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(oid)
                .claim("oid", oid)
                .claim("azp", clientId.toString())
                .claim("tid", TENANT_ID)
                .claim("ver", "2.0")
                .claim("roles", roles)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .jwtID(UUID.randomUUID().toString())
                // No "scp" - its presence marks a delegated token, which the validator prohibits.
                .build();

        final SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(KEY));
        } catch (final JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    public static String bearerFor(final List<String> roles) {
        return "Bearer " + token(UUID.randomUUID(), roles);
    }
}

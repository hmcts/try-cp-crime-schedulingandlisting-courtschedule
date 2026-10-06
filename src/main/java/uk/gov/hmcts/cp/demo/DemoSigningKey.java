package uk.gov.hmcts.cp.demo;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.ParseException;

/**
 * The RSA keypair this sandbox signs its demo access tokens with, and verifies them against.
 *
 * <p>Supplied as a JWK JSON document from the {@code amp-{env}} key vault in every deployed
 * environment. It <b>must</b> be stable across restarts: Flux redeploys the pod on every merge to
 * master, and a key generated at startup would invalidate every token already handed to a consumer —
 * surfacing as intermittent 401s that look like a platform fault rather than a key rotation.
 *
 * <p>A blank configuration value generates an ephemeral key so the service runs locally with no
 * setup. That path logs a warning and is never appropriate for a deployed environment.
 */
@Component
@Slf4j
public class DemoSigningKey {

    private static final int KEY_SIZE_BITS = 2048;
    private static final String KEY_ID = "try-it-now-demo-key-1";

    private final RSAKey keypair;

    public DemoSigningKey(@Value("${demo.signing-key-jwk:}") final String signingKeyJwk) {
        this.keypair = signingKeyJwk == null || signingKeyJwk.isBlank()
                ? generateEphemeral()
                : parse(signingKeyJwk);
    }

    /** The private key, for signing. Never leaves this process. */
    public RSAKey privateKey() {
        return keypair;
    }

    /**
     * The public half only — what {@code /.well-known/jwks.json} publishes and Nimbus verifies with.
     */
    public JWKSet publicJwkSet() {
        return new JWKSet(keypair.toPublicJWK());
    }

    private static RSAKey parse(final String signingKeyJwk) {
        final RSAKey parsed;
        try {
            parsed = RSAKey.parse(signingKeyJwk);
        } catch (final ParseException e) {
            throw new IllegalStateException(
                    "demo.signing-key-jwk is not a valid JWK document. Expected the JSON produced by "
                            + "an RSA JWK export, supplied from the amp-{env} key vault.", e);
        }
        if (!parsed.isPrivate()) {
            throw new IllegalStateException(
                    "demo.signing-key-jwk contains only a public key — this service must be able to sign.");
        }
        log.info("Demo signing key loaded from configuration kid:{}", parsed.getKeyID());
        return parsed;
    }

    private static RSAKey generateEphemeral() {
        final RSAKey generated;
        try {
            generated = new RSAKeyGenerator(KEY_SIZE_BITS)
                    .keyID(KEY_ID)
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();
        } catch (final JOSEException e) {
            throw new IllegalStateException("Could not generate an ephemeral demo signing key", e);
        }
        log.warn("DEMO KEY: no demo.signing-key-jwk configured — generated an EPHEMERAL signing key. "
                + "Every token issued dies with this process and will not verify on another replica. "
                + "Acceptable for local development only; set the vault secret in any deployed environment.");
        return generated;
    }
}

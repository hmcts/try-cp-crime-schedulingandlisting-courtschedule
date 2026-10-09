package uk.gov.hmcts.cp.demo;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.text.ParseException;
import java.util.Base64;

/**
 * The RSA keypair this sandbox signs its demo access tokens with, and verifies them against.
 *
 * <p>Supplied from the {@code amp-try-slc-{env}} key vault in every deployed environment, as either
 * a PKCS#8 PEM private key or a JWK JSON document. Terraform generates the PEM form
 * ({@code tls_private_key} in {@code infrastructure/main.tf}); JWK is still accepted for a key set
 * by hand. It <b>must</b> be stable across restarts: Flux redeploys the pod on every merge to
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
    private static final String PEM_PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_PKCS8_FOOTER = "-----END PRIVATE KEY-----";

    private final RSAKey keypair;

    public DemoSigningKey(@Value("${demo.signing-key-jwk:}") final String signingKeyJwk) {
        if (signingKeyJwk == null || signingKeyJwk.isBlank()) {
            this.keypair = generateEphemeral();
        } else if (signingKeyJwk.strip().startsWith("-----BEGIN")) {
            this.keypair = parsePem(signingKeyJwk);
        } else {
            this.keypair = parse(signingKeyJwk);
        }
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

    /**
     * Reads the PKCS#8 PEM that Terraform's {@code private_key_pem_pkcs8} produces. The public half
     * is derived from the CRT private key, and the key id is the same as the ephemeral path's, so
     * the published JWKS has the same shape whichever way the key arrived.
     */
    private static RSAKey parsePem(final String pem) {
        final String trimmed = pem.strip();
        if (!trimmed.startsWith(PEM_PKCS8_HEADER) || !trimmed.endsWith(PEM_PKCS8_FOOTER)) {
            throw new IllegalStateException(
                    "demo.signing-key-jwk is PEM but not a PKCS#8 private key. Expected "
                            + PEM_PKCS8_HEADER + ", as Terraform's private_key_pem_pkcs8 produces.");
        }
        final String body = trimmed
                .substring(PEM_PKCS8_HEADER.length(), trimmed.length() - PEM_PKCS8_FOOTER.length())
                .replaceAll("\\s", "");
        final RSAKey parsed;
        try {
            final KeyFactory factory = KeyFactory.getInstance("RSA");
            final RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            final RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            parsed = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(KEY_ID)
                    .keyUse(KeyUse.SIGNATURE)
                    .build();
        } catch (final GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException(
                    "demo.signing-key-jwk is not a valid PKCS#8 RSA private key.", e);
        }
        log.info("Demo signing key loaded from configuration (PEM) kid:{}", parsed.getKeyID());
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

package uk.gov.hmcts.cp.demo;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key formats the vault secret may hold. Terraform writes PKCS#8 PEM; a key set by hand is JWK.
 * Both must load as the same private key, or the pipeline-generated key would fail startup.
 */
class DemoSigningKeyTest {

    @Test
    @DisplayName("a PKCS#8 PEM key, as Terraform's private_key_pem_pkcs8 writes it, loads and signs")
    void pemKeyLoadsAndSigns() throws Exception {
        final KeyPair pair = rsaKeyPair();

        final DemoSigningKey signingKey = new DemoSigningKey(pkcs8Pem(pair));

        final RSAKey key = signingKey.privateKey();
        assertThat(key.isPrivate()).isTrue();
        assertThat(key.getKeyID()).isEqualTo("try-it-now-demo-key-1");
        assertThat(key.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(key.toRSAPublicKey()).isEqualTo(pair.getPublic());
        assertThat(signsAndVerifies(signingKey)).isTrue();
    }

    @Test
    @DisplayName("the PEM path tolerates the trailing newline a vault secret usually carries")
    void pemWithSurroundingWhitespaceLoads() throws Exception {
        final DemoSigningKey signingKey = new DemoSigningKey("\n" + pkcs8Pem(rsaKeyPair()) + "\n");

        assertThat(signingKey.privateKey().isPrivate()).isTrue();
    }

    @Test
    @DisplayName("a JWK document still loads, so a key set by hand keeps working")
    void jwkKeyStillLoads() throws Exception {
        final RSAKey jwk = new DemoSigningKey("").privateKey();

        final DemoSigningKey signingKey = new DemoSigningKey(jwk.toJSONString());

        assertThat(signingKey.privateKey()).isEqualTo(jwk);
    }

    @Test
    @DisplayName("PEM that is not a PKCS#8 private key fails startup, naming the expected format")
    void nonPkcs8PemFails() {
        final String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----";

        assertThatThrownBy(() -> new DemoSigningKey(pkcs1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }

    @Test
    @DisplayName("a PKCS#8 header around a body that is not an RSA key fails startup")
    void corruptPemFails() {
        final String corrupt = "-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----";

        assertThatThrownBy(() -> new DemoSigningKey(corrupt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a valid PKCS#8 RSA private key");
    }

    private static KeyPair rsaKeyPair() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pkcs8Pem(final KeyPair pair) {
        final String body = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    private static boolean signsAndVerifies(final DemoSigningKey signingKey) throws Exception {
        final JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload("probe"));
        jws.sign(new RSASSASigner(signingKey.privateKey()));
        final RSAKey publicKey = signingKey.publicJwkSet().getKeys().get(0).toRSAKey();
        return JWSObject.parse(jws.serialize()).verify(new RSASSAVerifier(publicKey));
    }
}

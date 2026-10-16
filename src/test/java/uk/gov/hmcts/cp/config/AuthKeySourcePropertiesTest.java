package uk.gov.hmcts.cp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.auth.AuthMode;
import uk.gov.hmcts.cp.auth.EntraAuthProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The startup check that stops the two token realms being confused.
 *
 * <p>Why this matters more than it looks: {@code auth.*} defaults to the self-issued identifiers so
 * local development needs no configuration. An environment set to {@code ENTRA} that inherits those
 * defaults would verify real Entra tokens against an issuer that does not exist — every request a
 * 401, nothing in the logs saying why. These tests pin the failure to startup instead.
 */
class AuthKeySourcePropertiesTest {

    private static final String SELF_TENANT = "00000000-0000-0000-0000-0000000000aa";
    private static final String SELF_AUDIENCE = "api://try-cp-crime-schedulingandlisting-courtschedule";
    private static final String SELF_ISSUER =
            "https://try-it-now.amp.hmcts.internal/00000000-0000-0000-0000-0000000000aa/v2.0";

    private static final String REAL_TENANT = "531ff96d-0ae9-462a-8d2d-bec7c0b42082";
    private static final String REAL_AUDIENCE = "9b6b1c6e-4a1f-4c4d-9c10-6e4f0a1d2b33";
    private static final String REAL_ISSUER = "https://login.microsoftonline.com/" + REAL_TENANT + "/v2.0";

    @Test
    @DisplayName("entra_mode_should_start_when_all_three_identifiers_are_real")
    void startsWhenFullyConfigured() {
        assertThatCode(() -> assertEntraConfigured(REAL_TENANT, REAL_AUDIENCE, REAL_ISSUER, ""))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("entra_mode_should_fail_startup_naming_AUTH_TENANT_ID_when_the_tenant_is_the_sandbox_one")
    void failsOnSelfIssuedTenant() {
        assertThatThrownBy(() -> assertEntraConfigured(SELF_TENANT, REAL_AUDIENCE, REAL_ISSUER, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_TENANT_ID");
    }

    @Test
    @DisplayName("entra_mode_should_fail_startup_naming_AUTH_AUDIENCE_when_the_audience_is_the_sandbox_one")
    void failsOnSelfIssuedAudience() {
        assertThatThrownBy(() -> assertEntraConfigured(REAL_TENANT, SELF_AUDIENCE, REAL_ISSUER, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_AUDIENCE");
    }

    @Test
    @DisplayName("entra_mode_should_fail_startup_naming_AUTH_ISSUER_when_the_issuer_is_the_sandbox_one")
    void failsOnSelfIssuedIssuer() {
        assertThatThrownBy(() -> assertEntraConfigured(REAL_TENANT, REAL_AUDIENCE, SELF_ISSUER, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_ISSUER");
    }

    /**
     * A plaintext JWKS fetch would let anything on the path choose the key that verifies a token.
     * Nimbus would accept the URL, so the refusal has to happen here.
     */
    @Test
    @DisplayName("entra_mode_should_fail_startup_when_the_jwks_uri_is_not_https")
    void failsOnPlaintextJwks() {
        assertThatThrownBy(() -> assertEntraConfigured(
                REAL_TENANT, REAL_AUDIENCE, REAL_ISSUER, "http://login.microsoftonline.com/keys"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    /**
     * The self-issued realm is the one case where the sandbox identifiers are correct, so the check
     * must not run at all.
     */
    @Test
    @DisplayName("self_issued_mode_should_start_on_the_sandbox_identifiers")
    void selfIssuedModeSkipsTheCheck() {
        assertThatCode(() -> new AuthKeySourceProperties(
                TokenKeySource.SELF_ISSUED, SELF_TENANT, SELF_AUDIENCE, SELF_ISSUER,
                properties(SELF_TENANT, SELF_AUDIENCE, SELF_ISSUER, "")))
                .doesNotThrowAnyException();
    }

    private static void assertEntraConfigured(final String tenantId, final String audience,
                                              final String issuer, final String jwksUri) {
        AuthKeySourceProperties.assertEntraConfigured(
                properties(tenantId, audience, issuer, jwksUri), SELF_TENANT, SELF_AUDIENCE, SELF_ISSUER);
    }

    private static EntraAuthProperties properties(final String tenantId, final String audience,
                                                  final String issuer, final String jwksUri) {
        return new EntraAuthProperties(AuthMode.ENFORCE, tenantId, audience, issuer, jwksUri, 60, 600);
    }
}

package uk.gov.hmcts.cp.demo;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.auth.AuthMode;
import uk.gov.hmcts.cp.auth.AuthorizationPolicy;
import uk.gov.hmcts.cp.auth.EntraAuthProperties;
import uk.gov.hmcts.cp.auth.EntraTokenValidator;
import uk.gov.hmcts.cp.auth.TokenValidationException;
import uk.gov.hmcts.cp.auth.ValidatedCaller;
import uk.gov.hmcts.cp.services.ClockService;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the sandbox does not simulate token validation but performs it.
 *
 * <p>The validator exercised here is the file copied unchanged from the live service. If a future
 * edit to {@link DemoTokenMinter} drops a claim that validator requires — {@code ver}, {@code tid},
 * the {@code sub}/{@code oid} equality that marks an app-only token, or the absence of {@code scp} —
 * these tests fail rather than the sandbox quietly accepting tokens the real API would reject.
 */
class DemoTokenRoundTripTest {

    private static final String TENANT_ID = "00000000-0000-0000-0000-0000000000aa";
    private static final String AUDIENCE = "api://try-it-now-court-schedule";
    private static final String ISSUER = "https://try-it-now.example.internal/" + TENANT_ID + "/v2.0";

    private final DemoSigningKey signingKey = new DemoSigningKey("");
    private final EntraAuthProperties properties = new EntraAuthProperties(
            AuthMode.ENFORCE, TENANT_ID, AUDIENCE, ISSUER, "", 60, 600);
    private final ClockService clockService = new ClockService(Clock.systemUTC());
    private final DemoTokenMinter minter = new DemoTokenMinter(signingKey, properties, clockService, 3600);
    private final DemoClientRegistry registry = new DemoClientRegistry();
    private final AuthorizationPolicy policy = new AuthorizationPolicy("/");

    private EntraTokenValidator validator() {
        final JWKSource<SecurityContext> source = new ImmutableJWKSet<>(signingKey.publicJwkSet());
        return new EntraTokenValidator(properties, source);
    }

    @Test
    @DisplayName("a minted token passes the real validator and yields the client id from azp")
    void mintedTokenValidates() throws TokenValidationException {
        final DemoClient reader = registry.find(DemoClientRegistry.READER_CLIENT_ID.toString()).orElseThrow();

        final ValidatedCaller caller = validator().validate("Bearer " + minter.mint(reader));

        assertThat(caller.verified()).isTrue();
        assertThat(caller.clientId()).isEqualTo(DemoClientRegistry.READER_CLIENT_ID);
        assertThat(caller.hasRole(AuthorizationPolicy.ROLE_READ)).isTrue();
    }

    @Test
    @DisplayName("the role-less demo client produces a genuine 403, not a canned one")
    void rolelessClientIsRefused() {
        final DemoClient noRole = registry.find(DemoClientRegistry.NO_ROLE_CLIENT_ID.toString()).orElseThrow();
        final String token = minter.mint(noRole);

        // Rejected by the validator itself, not by AuthorizationPolicy: an app registration with no
        // role assignment yields an empty "roles" claim, and the validator treats that as MISSING_ROLE
        // before a policy check is ever reached. Both routes answer 403 insufficient_scope, so the
        // consumer-visible behaviour is identical - this asserts which layer actually refuses, so the
        // test keeps telling the truth about the copied validator rather than about its author.
        assertThatThrownBy(() -> validator().validate("Bearer " + token))
                .isInstanceOf(TokenValidationException.class)
                .extracting(ex -> ((TokenValidationException) ex).getReason())
                .isEqualTo(TokenValidationException.Reason.MISSING_ROLE);
    }

    @Test
    @DisplayName("both role failures are 403 insufficient_scope, never 401")
    void roleFailuresAreAuthorisationNotAuthentication() {
        assertThat(TokenValidationException.Reason.MISSING_ROLE.isAuthenticationFailure()).isFalse();
        assertThat(TokenValidationException.Reason.INSUFFICIENT_ROLE.isAuthenticationFailure()).isFalse();
        assertThat(TokenValidationException.Reason.MISSING_ROLE.getErrorCode()).isEqualTo("insufficient_scope");
    }

    @Test
    @DisplayName("a caller holding an unrecognised role is refused by the policy")
    void unrecognisedRoleIsRefusedByThePolicy() {
        final ValidatedCaller wrongRole = new ValidatedCaller(
                DemoClientRegistry.NO_ROLE_CLIENT_ID, java.util.List.of("app.write"), true);

        assertThatThrownBy(() -> policy.assertAuthorized(wrongRole))
                .isInstanceOf(TokenValidationException.class)
                .extracting(ex -> ((TokenValidationException) ex).getReason())
                .isEqualTo(TokenValidationException.Reason.INSUFFICIENT_ROLE);
    }

    @Test
    @DisplayName("a token signed by a different key is rejected")
    void tokenFromAnotherIssuerIsRejected() {
        final DemoSigningKey otherKey = new DemoSigningKey("");
        final DemoTokenMinter otherMinter = new DemoTokenMinter(otherKey, properties, clockService, 3600);
        final DemoClient reader = registry.find(DemoClientRegistry.READER_CLIENT_ID.toString()).orElseThrow();

        final String foreignToken = otherMinter.mint(reader);

        assertThatThrownBy(() -> validator().validate("Bearer " + foreignToken))
                .isInstanceOf(TokenValidationException.class);
    }

    @Test
    @DisplayName("a tampered token is rejected")
    void tamperedTokenIsRejected() {
        final DemoClient reader = registry.find(DemoClientRegistry.READER_CLIENT_ID.toString()).orElseThrow();
        final String token = minter.mint(reader);
        final String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "Zm9yZ2Vk";

        assertThatThrownBy(() -> validator().validate("Bearer " + tampered))
                .isInstanceOf(TokenValidationException.class);
    }

    @Test
    @DisplayName("a missing or non-Bearer authorization header is rejected")
    void missingHeaderIsRejected() {
        assertThatThrownBy(() -> validator().validate(null))
                .isInstanceOf(TokenValidationException.class)
                .extracting(ex -> ((TokenValidationException) ex).getReason())
                .isEqualTo(TokenValidationException.Reason.MISSING_AUTHORIZATION_HEADER);

        assertThatThrownBy(() -> validator().validate("Basic abc123"))
                .isInstanceOf(TokenValidationException.class)
                .extracting(ex -> ((TokenValidationException) ex).getReason())
                .isEqualTo(TokenValidationException.Reason.UNSUPPORTED_SCHEME);
    }

    @Test
    @DisplayName("the published JWKS carries only the public key")
    void jwksNeverLeaksThePrivateKey() {
        assertThat(signingKey.publicJwkSet().getKeys()).hasSize(1);
        assertThat(signingKey.publicJwkSet().getKeys().getFirst().isPrivate()).isFalse();
        assertThat(signingKey.publicJwkSet().toJSONObject(true).toString()).doesNotContain("\"d\"");
    }
}

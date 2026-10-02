package uk.gov.hmcts.cp.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationPolicyTest {

    /** Matches application.yaml, where the actuator sits on the context root for chart-java's probes. */
    private final AuthorizationPolicy policy = new AuthorizationPolicy("/");

    @ParameterizedTest
    @ValueSource(strings = {"/health", "/info", "/prometheus"})
    @DisplayName("actuator probes are exempt even though the actuator base path is '/'")
    void actuatorProbesAreExempt(final String path) {
        // Regression guard. With base-path "/", the inherited prefix rule degenerates to
        // "/health".startsWith("//") == false. If these paths stop being listed explicitly the
        // liveness probe is answered 401 and the pod never reaches ready — a failure that presents
        // as a broken deployment rather than an auth misconfiguration.
        assertThat(policy.isExemptFromValidation(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/oauth2/v2.0/token", "/.well-known/jwks.json", "/scenarios", "/", "/error"})
    @DisplayName("the demo discovery endpoints are reachable without a token")
    void demoEndpointsAreExempt(final String path) {
        assertThat(policy.isExemptFromValidation(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/case/TIN-ALLOCATED-01/courtschedule",
        "/health/../case/TIN-ALLOCATED-01/courtschedule",
        "/scenarios/secret",
        "/oauth2/v2.0/token/extra"})
    @DisplayName("data endpoints are not exempt, and no exemption leaks to a longer path")
    void dataEndpointsRequireAToken(final String path) {
        assertThat(policy.isExemptFromValidation(path)).isFalse();
    }

    @Test
    @DisplayName("a non-root actuator base path still exempts its subtree")
    void nonRootActuatorBasePathIsHonoured() {
        final AuthorizationPolicy scoped = new AuthorizationPolicy("/actuator");
        assertThat(scoped.isExemptFromValidation("/actuator")).isTrue();
        assertThat(scoped.isExemptFromValidation("/actuator/health")).isTrue();
        assertThat(scoped.isExemptFromValidation("/actuatorfoo")).isFalse();
    }

    @Test
    @DisplayName("a caller holding no recognised role is refused")
    void roleIsEnforced() throws TokenValidationException {
        final ValidatedCaller reader = new ValidatedCaller(
                java.util.UUID.randomUUID(), java.util.List.of(AuthorizationPolicy.ROLE_READ), true);
        policy.assertAuthorized(reader);

        final ValidatedCaller roleless = new ValidatedCaller(
                java.util.UUID.randomUUID(), java.util.List.of(), true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> policy.assertAuthorized(roleless))
                .isInstanceOf(TokenValidationException.class)
                .extracting(ex -> ((TokenValidationException) ex).getReason())
                .isEqualTo(TokenValidationException.Reason.INSUFFICIENT_ROLE);
    }
}

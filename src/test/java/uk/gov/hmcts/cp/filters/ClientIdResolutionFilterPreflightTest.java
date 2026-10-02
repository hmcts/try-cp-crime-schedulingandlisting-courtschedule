package uk.gov.hmcts.cp.filters;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import uk.gov.hmcts.cp.auth.AuthorizationPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the CORS preflight exemption.
 *
 * <p>Without it the browser's {@code OPTIONS} probe — which carries no {@code Authorization} header
 * by specification — is answered 401, and the browser abandons the exchange before sending the real
 * request. "Try it out" on the published Swagger UI then fails while the same call from curl
 * succeeds, which is a confusing enough symptom to be worth a test of its own.
 */
class ClientIdResolutionFilterPreflightTest {

    private final ClientIdResolutionFilter filter = new ClientIdResolutionFilter(
            null, new AuthorizationPolicy("/"), null, new SimpleMeterRegistry());

    @Test
    @DisplayName("a CORS preflight bypasses token validation")
    void preflightIsNotFiltered() {
        final MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/case/X/courtschedule");
        preflight.addHeader(HttpHeaders.ORIGIN, "https://hmcts.github.io");
        preflight.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        assertThat(filter.shouldNotFilter(preflight)).isTrue();
    }

    @Test
    @DisplayName("the real cross-origin request is still validated")
    void actualRequestIsFiltered() {
        final MockHttpServletRequest actual = new MockHttpServletRequest("GET", "/case/X/courtschedule");
        actual.addHeader(HttpHeaders.ORIGIN, "https://hmcts.github.io");

        assertThat(filter.shouldNotFilter(actual)).isFalse();
    }

    @Test
    @DisplayName("an OPTIONS request that is not a preflight is still validated")
    void bareOptionsIsFiltered() {
        // No Origin and no Access-Control-Request-Method, so this is not a preflight and must not
        // become a way to reach a protected path unauthenticated.
        final MockHttpServletRequest bareOptions = new MockHttpServletRequest("OPTIONS", "/case/X/courtschedule");

        assertThat(filter.shouldNotFilter(bareOptions)).isFalse();
    }
}

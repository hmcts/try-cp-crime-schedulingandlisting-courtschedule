package uk.gov.hmcts.amp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Is the deployed service alive?
 *
 * <p>The CNP pipeline runs this against a real deployment, with {@code TEST_URL} set by the
 * {@code Smoke Test - AKS <env>} stage. Keep it to liveness: anything asserting behaviour belongs in
 * the functional suite, because a smoke failure clears the helm release and blocks the build.
 *
 * <p>This also guards the probe-path regression directly. {@code /health} must be reachable without
 * a token - chart-java probes {@code /health/liveness}, and when those were not exempt the pod never
 * became ready and the deploy burned its full 1250s {@code --wait}. See {@code AuthorizationPolicy}.
 */
class SmokeTest {

    private static final String DEFAULT_URL = "http://localhost:8080";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String testUrl = System.getenv().getOrDefault("TEST_URL", DEFAULT_URL);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Test
    @DisplayName("the deployed service answers /health with UP, and does so without a token")
    void healthIsUpAndUnauthenticated() throws IOException, InterruptedException {
        final HttpResponse<String> response = get("/health");

        assertThat(response.statusCode())
                .describedAs("GET %s/health - a 401 here means the actuator paths are no longer exempt "
                        + "in AuthorizationPolicy", testUrl)
                .isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("the liveness probe path chart-java actually uses is reachable")
    void livenessProbePathIsReachable() throws IOException, InterruptedException {
        // chart-java sets startupPath and livenessPath to /health/liveness, not /health. Exempting
        // only /health leaves this answering 401, which presents as a 40-minute helm timeout.
        assertThat(get("/health/liveness").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(final String path) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testUrl + path))
                .timeout(TIMEOUT)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

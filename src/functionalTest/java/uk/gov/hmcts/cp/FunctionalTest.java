package uk.gov.hmcts.cp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import uk.gov.hmcts.cp.auth.AuthorizationPolicy;
import uk.gov.hmcts.cp.demo.DemoClientRegistry;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Walks the journey the catalogue tells a consumer to walk, against a real deployment.
 *
 * <p>The CNP pipeline runs this after the smoke stage with {@code TEST_URL} set by
 * {@code Functional Test - AKS <env>}. Read {@code /scenarios}, swap demo credentials for a token at
 * {@code /oauth2/v2.0/token}, call the court schedule endpoint with it — and take both documented
 * refusals, the 401 for a bad secret and the 403 for the role-less client, because those are the
 * branches a consumer writes error handling against and the ones most likely to break silently.
 *
 * <p>Assertions are on <em>shape</em>, never on fixture values. Recordings supersede fixtures by
 * design, so pinning {@code HRG-123410} would make this suite fail the first time someone publishes
 * a recording — and a functional failure uninstalls the helm release
 * ({@code sectionDeployToAKS.groovy:189}), so a brittle assertion here costs a deployment. For the
 * same reason the case URNs are read from {@code /scenarios} rather than hardcoded: the sandbox
 * publishes which URN produces which status, and this suite takes it at its word. Every URN with a
 * given status is exercised, never just the first — {@code ScenarioCatalog.all()} iterates a
 * {@code Map.copyOf} map, whose order the JDK randomises per JVM run, so "the first 200 scenario"
 * is a different fixture on different pods.
 *
 * <p>In-process equivalents of the auth rules live in {@code DemoTokenRoundTripTest}. What only this
 * suite can prove is that they survive the deployed configuration — the vault-mounted signing key,
 * the ingress, and {@code AuthorizationPolicy} as the running pod sees it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FunctionalTest {

    private static final String DEFAULT_URL = "http://localhost:8080";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String FORM_MEDIA_TYPE = "application/x-www-form-urlencoded";

    private final String testUrl = System.getenv().getOrDefault("TEST_URL", DEFAULT_URL);
    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** The sandbox's own description of itself, which the rest of the suite navigates by. */
    private JsonNode scenarios;

    @BeforeAll
    void readTheSelfDocumentation() throws IOException, InterruptedException {
        final HttpResponse<String> response = get(AuthorizationPolicy.PATH_SCENARIOS, null);

        assertThat(response.statusCode())
                .describedAs("GET %s%s - this is the consumer's entry point and is unauthenticated "
                        + "on purpose", testUrl, AuthorizationPolicy.PATH_SCENARIOS)
                .isEqualTo(200);

        scenarios = mapper.readTree(response.body());
    }

    @Test
    @DisplayName("/scenarios publishes both demo clients, so a consumer can self-serve credentials")
    void scenariosPublishesTheDemoCredentials() {
        final JsonNode demoClients = scenarios.get("demoClients");

        assertThat(demoClients).isNotNull();
        assertThat(demoClients.isArray()).isTrue();
        assertThat(demoClients.toString())
                .describedAs("both the entitled and the role-less client must be advertised - the "
                        + "second is how a consumer exercises its 403 handling")
                .contains(DemoClientRegistry.READER_CLIENT_ID.toString())
                .contains(DemoClientRegistry.NO_ROLE_CLIENT_ID.toString());
    }

    @Test
    @DisplayName("the token endpoint issues a Bearer token for the entitled demo client")
    void tokenEndpointIssuesABearerToken() throws IOException, InterruptedException {
        final HttpResponse<String> response =
                requestToken(DemoClientRegistry.READER_CLIENT_ID.toString(), DemoClientRegistry.DEMO_SECRET);

        assertThat(response.statusCode()).isEqualTo(200);

        final JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("token_type").asText()).isEqualTo("Bearer");
        assertThat(body.path("expires_in").asInt()).isPositive();
        assertThat(body.path("access_token").asText())
                .describedAs("a signed JWT, so three dot-separated segments")
                .matches("[^.]+\\.[^.]+\\.[^.]+");
    }

    @Test
    @DisplayName("a wrong client secret is refused with invalid_client, not a canned error")
    void wrongSecretIsRefused() throws IOException, InterruptedException {
        final HttpResponse<String> response =
                requestToken(DemoClientRegistry.READER_CLIENT_ID.toString(), "not-the-published-secret");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("invalid_client");
    }

    @Test
    @DisplayName("the court schedule endpoint refuses an unauthenticated call")
    void courtScheduleRequiresTheToken() throws IOException, InterruptedException {
        final HttpResponse<String> response = get(pathsOfScenariosAnswering(200).getFirst(), null);

        assertThat(response.statusCode())
                .describedAs("a 200 here would mean the read API had been exempted from token "
                        + "validation - see AuthorizationPolicy")
                .isEqualTo(401);
        assertThat(response.body()).contains("invalid_token");
    }

    @Test
    @DisplayName("an entitled token fetches every published schedule, contract-shaped")
    void entitledTokenFetchesEverySchedule() throws IOException, InterruptedException {
        final String token = tokenFor(DemoClientRegistry.READER_CLIENT_ID);
        boolean sawAnIdentifiedHearing = false;

        for (final String path : pathsOfScenariosAnswering(200)) {
            final HttpResponse<String> response = get(path, token);

            assertThat(response.statusCode())
                    .describedAs("GET %s - /scenarios publishes this URN as a 200", path)
                    .isEqualTo(200);

            final JsonNode courtSchedule = mapper.readTree(response.body()).get("courtSchedule");
            assertThat(courtSchedule)
                    .describedAs("%s - the contract's top-level array", path)
                    .isNotNull();
            assertThat(courtSchedule.isArray())
                    .describedAs("%s - courtSchedule must be an array; an empty one is a valid 200, "
                            + "which is why this does not assert it is populated", path)
                    .isTrue();

            for (final JsonNode entry : courtSchedule) {
                for (final JsonNode hearing : entry.path("hearings")) {
                    assertThat(hearing.path("hearingId").asText())
                            .describedAs("%s - every hearing the contract defines carries an id", path)
                            .isNotBlank();
                    sawAnIdentifiedHearing = true;
                }
            }
        }

        assertThat(sawAnIdentifiedHearing)
                .describedAs("not one published 200 scenario carried a hearing - the sandbox exists to "
                        + "serve examples, so an estate of entirely empty schedules is a fault")
                .isTrue();
    }

    @Test
    @DisplayName("the role-less demo client is refused with 403 insufficient_scope")
    void unentitledClientIsRefused() throws IOException, InterruptedException {
        // A real 403 from the copied validator, not a fixture: the client's token verifies, it simply
        // carries no application role. See DemoTokenRoundTripTest for which layer refuses it.
        final HttpResponse<String> response =
                get(pathsOfScenariosAnswering(200).getFirst(), tokenFor(DemoClientRegistry.NO_ROLE_CLIENT_ID));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("insufficient_scope");
    }

    @Test
    @DisplayName("every URN published as a 404 answers 404, even for an entitled caller")
    void urnsPublishedAsNotFoundAnswerNotFound() throws IOException, InterruptedException {
        final String token = tokenFor(DemoClientRegistry.READER_CLIENT_ID);

        for (final String path : pathsOfScenariosAnswering(404)) {
            assertThat(get(path, token).statusCode())
                    .describedAs("GET %s - /scenarios publishes this URN as a 404", path)
                    .isEqualTo(404);
        }
    }

    /**
     * The paths of every scenario {@code /scenarios} says answers {@code status}, already substituted
     * with their case URNs by {@code ScenarioController}.
     */
    private List<String> pathsOfScenariosAnswering(final int status) {
        final List<String> paths = new ArrayList<>();

        for (final JsonNode scenario : scenarios.path("scenarios")) {
            if (scenario.path("status").asInt() == status) {
                paths.add(scenario.path("path").asText());
            }
        }

        assertThat(paths)
                .describedAs("no scenario published at %s answers %d - either the fixtures failed to "
                        + "seed, or stubs/scenarios.yaml no longer covers that outcome",
                        AuthorizationPolicy.PATH_SCENARIOS, status)
                .isNotEmpty();

        return paths;
    }

    private String tokenFor(final UUID clientId) throws IOException, InterruptedException {
        final HttpResponse<String> response =
                requestToken(clientId.toString(), DemoClientRegistry.DEMO_SECRET);

        assertThat(response.statusCode())
                .describedAs("could not mint a demo token for %s: %s", clientId, response.body())
                .isEqualTo(200);

        return mapper.readTree(response.body()).path("access_token").asText();
    }

    private HttpResponse<String> requestToken(final String clientId, final String clientSecret)
            throws IOException, InterruptedException {

        final String form = "grant_type=client_credentials"
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret);

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testUrl + AuthorizationPolicy.PATH_TOKEN))
                .timeout(TIMEOUT)
                .header("Content-Type", FORM_MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(final String path, final String bearerToken)
            throws IOException, InterruptedException {

        final HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(testUrl + path))
                .timeout(TIMEOUT)
                .GET();

        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }

        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

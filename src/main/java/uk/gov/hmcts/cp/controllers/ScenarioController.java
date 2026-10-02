package uk.gov.hmcts.cp.controllers;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.auth.AuthorizationPolicy;
import uk.gov.hmcts.cp.demo.DemoClientRegistry;
import uk.gov.hmcts.cp.openapi.api.CourtScheduleApi;
import uk.gov.hmcts.cp.scenarios.Scenario;
import uk.gov.hmcts.cp.scenarios.ScenarioCatalog;

import java.util.List;
import java.util.Map;

/**
 * Self-documents the sandbox: which case URNs produce which outcome, and which credentials to use.
 *
 * <p>Unauthenticated on purpose — a consumer needs this before it can obtain a token, and it exposes
 * nothing but the sandbox's own fixtures.
 */
@RestController
public class ScenarioController {

    private final ScenarioCatalog scenarioCatalog;
    private final DemoClientRegistry clientRegistry;

    public ScenarioController(final ScenarioCatalog scenarioCatalog, final DemoClientRegistry clientRegistry) {
        this.scenarioCatalog = scenarioCatalog;
        this.clientRegistry = clientRegistry;
    }

    @GetMapping(value = AuthorizationPolicy.PATH_SCENARIOS, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> scenarios() {
        return Map.of(
                "description", "Sandbox for the Scheduling and Listing Court Schedule API. Responses are "
                        + "fixtures held in this repository — no Common Platform data is reachable from here, "
                        + "and no real case data is present.",
                "howToCall", Map.of(
                        "1_getToken", "POST " + AuthorizationPolicy.PATH_TOKEN
                                + " (application/x-www-form-urlencoded) with grant_type=client_credentials,"
                                + " client_id and client_secret from demoClients below",
                        "2_callApi", "GET " + CourtScheduleApi.PATH_GET_COURT_SCHEDULE_BY_CASE_URN
                                + " with header Authorization: Bearer <access_token>",
                        "note", "Tokens are issued and verified by this sandbox alone. They are not Entra "
                                + "tokens and will not authenticate against any real HMCTS API."),
                "demoClients", clientRegistry.all().stream()
                        .map(client -> Map.of(
                                "client_id", client.clientId().toString(),
                                "client_secret", client.clientSecret(),
                                "roles", client.roles(),
                                "description", client.description()))
                        .toList(),
                "scenarios", describeScenarios());
    }

    private List<Map<String, Object>> describeScenarios() {
        return scenarioCatalog.all().stream()
                .map(this::describe)
                .toList();
    }

    private Map<String, Object> describe(final Scenario scenario) {
        return Map.of(
                "id", scenario.id() == null ? "" : scenario.id(),
                "caseUrn", scenario.caseUrn(),
                "status", scenario.status(),
                "summary", scenario.summary() == null ? "" : scenario.summary(),
                // Where this example came from. Surfaced deliberately: an example captured in a
                // lower environment should never be mistaken for production data by whoever is
                // evaluating the API against it.
                "recordedFrom", scenario.recordedFrom() == null ? "unknown" : scenario.recordedFrom(),
                "path", CourtScheduleApi.PATH_GET_COURT_SCHEDULE_BY_CASE_URN
                        .replace("{case_urn}", scenario.caseUrn()));
    }
}

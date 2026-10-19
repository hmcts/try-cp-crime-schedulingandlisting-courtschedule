package uk.gov.hmcts.amp.controllers;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.amp.auth.AuthorizationPolicy;
import uk.gov.hmcts.amp.auth.EntraAuthProperties;
import uk.gov.hmcts.amp.config.AuthKeySourceProperties;
import uk.gov.hmcts.cp.openapi.api.CourtScheduleApi;
import uk.gov.hmcts.amp.scenarios.Scenario;
import uk.gov.hmcts.amp.scenarios.ScenarioCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-documents the sandbox: which case URNs produce which outcome, and how to authenticate.
 *
 * <p>Unauthenticated on purpose — a consumer needs this before it can obtain a token, and it exposes
 * nothing but the sandbox's own fixtures.
 *
 * <p>It also reports which token realm is live, under {@code authentication.mode}. That is the only
 * way a caller — or the functional test — can tell whether to fetch a token from this service or
 * from Entra, since the demo endpoints simply do not exist in the latter case.
 *
 * <p><b>It does not return the demo client credentials.</b> This response is read by consumers
 * evaluating the live API, most of whom will be holding an Entra token from their own registration;
 * a client id and secret in the payload invites them to use the wrong one. The credentials are in
 * the service README, where someone looking for the demo journey will find them and nobody else
 * trips over them. Removing them from here changes no behaviour — {@code DemoTokenController} still
 * accepts them in the self-issued realm.
 */
@RestController
public class ScenarioController {

    private static final String MODE_SELF_ISSUED = "self-issued";
    private static final String MODE_ENTRA = "entra";

    private final ScenarioCatalog scenarioCatalog;
    private final AuthKeySourceProperties keySourceProperties;
    private final EntraAuthProperties authProperties;

    public ScenarioController(final ScenarioCatalog scenarioCatalog,
                              final AuthKeySourceProperties keySourceProperties,
                              final EntraAuthProperties authProperties) {
        this.scenarioCatalog = scenarioCatalog;
        this.keySourceProperties = keySourceProperties;
        this.authProperties = authProperties;
    }

    @GetMapping(value = AuthorizationPolicy.PATH_SCENARIOS, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> scenarios() {
        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("description", "Sandbox for the Scheduling and Listing Court Schedule API. Responses are "
                + "fixtures held in this repository — no Common Platform data is reachable from here, "
                + "and no real case data is present.");
        response.put("authentication", describeAuthentication());
        response.put("howToCall", describeHowToCall());
        response.put("scenarios", describeScenarios());
        return response;
    }

    private Map<String, Object> describeAuthentication() {
        final Map<String, Object> authentication = new LinkedHashMap<>();
        authentication.put("mode", keySourceProperties.isSelfIssued() ? MODE_SELF_ISSUED : MODE_ENTRA);
        authentication.put("requiredRole", AuthorizationPolicy.ROLE_READ);
        authentication.put("audience", authProperties.getAudience());
        authentication.put("issuer", authProperties.getIssuer());
        if (keySourceProperties.isSelfIssued()) {
            authentication.put("tokenEndpoint", AuthorizationPolicy.PATH_TOKEN);
            authentication.put("jwksEndpoint", AuthorizationPolicy.PATH_JWKS);
        } else {
            authentication.put("tokenEndpoint",
                    "https://login.microsoftonline.com/" + authProperties.getTenantId() + "/oauth2/v2.0/token");
            authentication.put("scope", authProperties.getAudience() + "/.default");
        }
        return authentication;
    }

    private Map<String, Object> describeHowToCall() {
        if (keySourceProperties.isSelfIssued()) {
            return Map.of(
                    "1_getToken", "POST " + AuthorizationPolicy.PATH_TOKEN
                            + " (application/x-www-form-urlencoded) with grant_type=client_credentials"
                            + " and the demo client_id and client_secret published in this service's"
                            + " README",
                    "2_callApi", "GET " + CourtScheduleApi.PATH_GET_COURT_SCHEDULE_BY_CASE_URN
                            + " with header Authorization: Bearer <access_token>",
                    "note", "Tokens are issued and verified by this sandbox alone. They are not Entra "
                            + "tokens and will not authenticate against any real HMCTS API.");
        }
        return Map.of(
                "1_getToken", "POST the tokenEndpoint above (application/x-www-form-urlencoded) with "
                        + "grant_type=client_credentials, the client_id and client_secret of your own "
                        + "Entra app registration, and scope as given above",
                "2_callApi", "GET " + CourtScheduleApi.PATH_GET_COURT_SCHEDULE_BY_CASE_URN
                        + " with header Authorization: Bearer <access_token>",
                "note", "This environment accepts real Microsoft Entra app-only access tokens. Your app "
                        + "registration must be assigned the " + AuthorizationPolicy.ROLE_READ + " app role; "
                        + "a token without it is rejected with 403. Browser-based Try-it-out cannot "
                        + "complete a client_credentials exchange against Entra — use curl or a client.");
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

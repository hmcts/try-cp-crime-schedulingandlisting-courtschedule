package uk.gov.hmcts.cp.scenarios;

/**
 * One entry as written in {@code stubs/scenarios.yaml}, before its body has been read or checked.
 *
 * @param id      stable identifier, shown on {@code /scenarios}
 * @param caseUrn the case URN a consumer calls to reach this scenario
 * @param status  the HTTP status to answer with
 * @param body    file name under {@code stubs/}, holding the response body
 * @param summary one line describing what this scenario demonstrates
 */
public record ScenarioDefinition(String id, String caseUrn, int status, String body, String summary) {
}

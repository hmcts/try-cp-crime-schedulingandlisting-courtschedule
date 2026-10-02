package uk.gov.hmcts.cp.scenarios;

import uk.gov.hmcts.cp.openapi.model.CourtScheduleResponse;
import uk.gov.hmcts.cp.openapi.model.ErrorResponse;

/**
 * A published example, materialised from the store and ready to serve.
 *
 * <p>Holds the parsed model rather than raw JSON, so the response a consumer receives is
 * re-serialised from {@link CourtScheduleResponse} and cannot contain a field the contract does not
 * define. The raw text that a reviewer approved stays in the store.
 *
 * @param recordedFrom the source environment, surfaced on {@code /scenarios} so nobody mistakes a
 *                     capture from a lower environment for production data
 */
public record Scenario(String id,
                       String caseUrn,
                       int status,
                       String summary,
                       String recordedFrom,
                       CourtScheduleResponse schedule,
                       ErrorResponse error) {

    public boolean isSuccess() {
        return ContractValidator.isSuccess(status);
    }
}

package uk.gov.hmcts.cp.domain;

import jakarta.validation.constraints.NotBlank;

/**
 * Makes a stored recording live.
 *
 * @param scenarioId short identifier shown on {@code /scenarios}, e.g. {@code allocated}
 * @param summary    one line describing what it demonstrates; overrides the one given at ingest
 */
public record PublishRequest(

        @NotBlank(message = "scenarioId is required")
        String scenarioId,

        String summary) {
}

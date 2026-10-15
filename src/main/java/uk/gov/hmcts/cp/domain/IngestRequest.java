package uk.gov.hmcts.cp.domain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A captured response being offered to the sandbox.
 *
 * @param caseUrn      the URN a consumer will call to reach this example
 * @param httpStatus   the status the real service answered with
 * @param body         the response body as JSON <b>text</b>. A string rather than a nested object so
 *                     the exact characters that are validated are the exact characters stored and
 *                     later served — re-serialising a parsed tree would reformat it.
 * @param recordedFrom the source environment. Held so that a payload which looks wrong later can be
 *                     traced to where it came from, and so nobody mistakes a dev capture for
 *                     production data.
 * @param summary      one line describing what this example demonstrates, shown on {@code /scenarios}
 */
public record IngestRequest(

        @NotBlank(message = "caseUrn is required")
        String caseUrn,

        @Min(value = 100, message = "httpStatus must be a valid HTTP status")
        @Max(value = 599, message = "httpStatus must be a valid HTTP status")
        int httpStatus,

        @NotBlank(message = "body is required")
        String body,

        @NotBlank(message = "recordedFrom is required")
        @Pattern(regexp = "dev|ste|sit", message = "recordedFrom must be one of: dev, ste, sit")
        String recordedFrom,

        String summary) {
}

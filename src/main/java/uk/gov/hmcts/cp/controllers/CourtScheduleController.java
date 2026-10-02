package uk.gov.hmcts.cp.controllers;

import lombok.extern.slf4j.Slf4j;
import org.owasp.encoder.Encode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.openapi.api.CourtScheduleApi;
import uk.gov.hmcts.cp.openapi.model.CourtScheduleResponse;
import uk.gov.hmcts.cp.scenarios.Scenario;
import uk.gov.hmcts.cp.scenarios.ScenarioCatalog;
import uk.gov.hmcts.cp.scenarios.ScenarioFailureException;
import uk.gov.hmcts.cp.services.ErrorResponseFactory;

/**
 * Serves the court schedule endpoint from canned scenarios.
 *
 * <p>Implements the generated {@link CourtScheduleApi} rather than declaring its own mapping, so the
 * path, the parameter and the response type are the contract's and cannot drift from it. There is no
 * client, no mapper and no backend call — this service has no route to the Common Platform and is not
 * intended to acquire one.
 */
@RestController
@Slf4j
public class CourtScheduleController implements CourtScheduleApi {

    private final ScenarioCatalog scenarioCatalog;
    private final ErrorResponseFactory errorResponseFactory;

    public CourtScheduleController(final ScenarioCatalog scenarioCatalog,
                                   final ErrorResponseFactory errorResponseFactory) {
        this.scenarioCatalog = scenarioCatalog;
        this.errorResponseFactory = errorResponseFactory;
    }

    @Override
    public ResponseEntity<CourtScheduleResponse> getCourtScheduleByCaseUrn(final String caseUrn) {
        final Scenario scenario = scenarioCatalog.findByCaseUrn(caseUrn).orElse(null);

        if (scenario == null) {
            // An unrecognised URN behaves like the real API does for a case with no data, rather than
            // hinting that a magic value was expected.
            log.info("No scenario for caseUrn:{} — answering 404", Encode.forJava(caseUrn));
            throw new ScenarioFailureException(404, errorResponseFactory.notFound(
                    "No court schedule exists for the supplied case URN."));
        }

        if (!scenario.isSuccess()) {
            throw new ScenarioFailureException(scenario.status(),
                    errorResponseFactory.withCurrentTrace(scenario.error()));
        }

        log.info("Serving scenario:{} for caseUrn:{}", scenario.id(), Encode.forJava(caseUrn));
        return ResponseEntity.ok(scenario.schedule());
    }
}

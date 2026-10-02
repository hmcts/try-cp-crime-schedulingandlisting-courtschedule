package uk.gov.hmcts.cp.scenarios;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.openapi.model.CourtScheduleResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The build-time half of the contract gate.
 *
 * <p>Runs without a database on purpose, so a fixture that has drifted from the published contract
 * fails {@code ./gradlew build} rather than waiting for a deployment. The ingest endpoint applies
 * the same check through the same {@link ContractValidator}, so the two cannot diverge.
 */
class FixtureLoaderTest {

    private final ContractValidator contractValidator = new ContractValidator();
    private final FixtureLoader loader = new FixtureLoader(contractValidator);

    @Test
    @DisplayName("every committed fixture matches the published contract")
    void everyFixtureMatchesTheContract() {
        assertThatCode(loader::load).doesNotThrowAnyException();
        assertThat(loader.load()).isNotEmpty();
    }

    @Test
    @DisplayName("the documented scenarios are all present")
    void documentedScenariosArePresent() {
        final List<String> expected = List.of(
                "TIN-ALLOCATED-01", "TIN-WEEKCOMM-01", "TIN-MULTI-01", "TIN-EMPTY-01",
                "TIN-NOTFOUND-01", "TIN-BADREQUEST-01", "TIN-SERVERERROR-01");

        assertThat(loader.load()).extracting(Fixture::caseUrn)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("the allocated fixture nests hearings under courtSchedule, as the schema requires")
    void allocatedFixtureHasTheContractShape() {
        // Guards the specific defect in the published spec's own examples, which omit the
        // courtSchedule wrapper and model hearings as an object. A fixture copied from those
        // examples would deserialise to an empty response.
        final Fixture allocated = loader.load().stream()
                .filter(f -> f.caseUrn().equals("TIN-ALLOCATED-01"))
                .findFirst().orElseThrow();

        final CourtScheduleResponse response = contractValidator.parseSchedule(allocated.body());

        assertThat(response.getCourtSchedule()).hasSize(1);
        assertThat(response.getCourtSchedule().getFirst().getHearings()).hasSize(1);
    }

    @Test
    @DisplayName("a body carrying an undeclared field is rejected, naming the field")
    void undeclaredFieldIsRejected() {
        // The live service returns courtHouse and lastUpdatedTime on a hearing; the Hearing schema
        // declares neither. This is the check that will reject the first real recording - correctly.
        final String withUndeclaredField = """
                {"courtSchedule":[{"hearings":[{"hearingId":"HRG-1","lastUpdatedTime":"2026-07-09T09:00:00Z"}]}]}""";

        assertThatThrownBy(() -> contractValidator.validate(200, withUndeclaredField))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("lastUpdatedTime");
    }

    @Test
    @DisplayName("an error body is checked against ErrorResponse, not the schedule model")
    void errorBodiesUseTheErrorEnvelope() {
        final String error = """
                {"error":"not_found","message":"none","details":{},"timestamp":"2026-01-01T00:00:00Z","traceId":"t"}""";

        assertThatCode(() -> contractValidator.validate(404, error)).doesNotThrowAnyException();
        assertThatThrownBy(() -> contractValidator.validate(200, error))
                .isInstanceOf(ContractViolationException.class);
    }
}

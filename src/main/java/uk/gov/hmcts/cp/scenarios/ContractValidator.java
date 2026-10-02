package uk.gov.hmcts.cp.scenarios;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.openapi.model.CourtScheduleResponse;
import uk.gov.hmcts.cp.openapi.model.ErrorResponse;

/**
 * The contract gate.
 *
 * <p>Deserialises a body into the model generated from the published OpenAPI contract, with
 * unknown-property checking on. A body carrying a field the contract does not declare, or nesting
 * one at the wrong depth, is rejected here rather than being served.
 *
 * <p>Both paths into the store use this same class on purpose — the fixtures seeded at boot and the
 * recordings arriving at the ingest endpoint. If they validated differently, a fixture could pass
 * checks that a recording of the same response would fail, and the two would drift.
 */
@Component
public class ContractValidator {

    private final ObjectMapper strict = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Checks a body against the contract for the status it was returned with.
     *
     * @throws ContractViolationException when the body does not fit the contract for that status
     */
    public void validate(final int httpStatus, final String body) {
        final Class<?> expected = isSuccess(httpStatus) ? CourtScheduleResponse.class : ErrorResponse.class;
        try {
            strict.readValue(body, expected);
        } catch (final JsonProcessingException e) {
            throw new ContractViolationException(
                    "Body does not match the published contract for HTTP " + httpStatus
                            + " (expected " + expected.getSimpleName() + "): " + rootMessage(e), e);
        }
    }

    /** Parses a body already known to be valid. Used when materialising a stored recording. */
    public CourtScheduleResponse parseSchedule(final String body) {
        try {
            return strict.readValue(body, CourtScheduleResponse.class);
        } catch (final JsonProcessingException e) {
            throw new ContractViolationException("Stored body is not a valid CourtScheduleResponse: "
                    + rootMessage(e), e);
        }
    }

    /** Parses a stored error envelope. */
    public ErrorResponse parseError(final String body) {
        try {
            return strict.readValue(body, ErrorResponse.class);
        } catch (final JsonProcessingException e) {
            throw new ContractViolationException("Stored body is not a valid ErrorResponse: "
                    + rootMessage(e), e);
        }
    }

    public static boolean isSuccess(final int httpStatus) {
        return httpStatus >= 200 && httpStatus < 300;
    }

    /**
     * Jackson's own first line names the field and its location, which is the useful part. The
     * stack trace and reference chain below it are noise to whoever is holding a failed capture.
     */
    private static String rootMessage(final JsonProcessingException e) {
        final String message = e.getOriginalMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}

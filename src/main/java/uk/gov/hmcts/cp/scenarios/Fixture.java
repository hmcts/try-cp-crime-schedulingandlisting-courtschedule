package uk.gov.hmcts.cp.scenarios;

/**
 * A committed example, read from {@code src/main/resources/stubs/} and validated against the
 * contract. Seeded into the store at boot so a fresh environment is never empty.
 */
public record Fixture(String id, String caseUrn, int httpStatus, String summary, String body) {
}

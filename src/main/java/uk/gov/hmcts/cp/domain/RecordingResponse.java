package uk.gov.hmcts.cp.domain;

import uk.gov.hmcts.cp.entity.RecordingEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored recording, as the admin API returns it.
 *
 * <p>Includes the body, because the reviewer deciding whether this is fit to be public has to be
 * able to read what it actually contains.
 */
public record RecordingResponse(
        UUID id,
        String caseUrn,
        int httpStatus,
        String status,
        String scenarioId,
        String summary,
        String recordedFrom,
        Instant recordedAt,
        String recordedBy,
        Instant publishedAt,
        String publishedBy,
        String body) {

    public static RecordingResponse from(final RecordingEntity entity) {
        return new RecordingResponse(
                entity.getId(),
                entity.getCaseUrn(),
                entity.getHttpStatus(),
                entity.getStatus().name(),
                entity.getScenarioId(),
                entity.getSummary(),
                entity.getRecordedFrom(),
                entity.getRecordedAt(),
                entity.getRecordedBy(),
                entity.getPublishedAt(),
                entity.getPublishedBy(),
                entity.getBody());
    }
}

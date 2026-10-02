package uk.gov.hmcts.cp.recordings;

import lombok.extern.slf4j.Slf4j;
import org.owasp.encoder.Encode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.domain.IngestRequest;
import uk.gov.hmcts.cp.domain.PublishRequest;
import uk.gov.hmcts.cp.entity.RecordingEntity;
import uk.gov.hmcts.cp.entity.RecordingStatus;
import uk.gov.hmcts.cp.repository.RecordingRepository;
import uk.gov.hmcts.cp.scenarios.ContractValidator;
import uk.gov.hmcts.cp.services.ClockService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores captured examples, and decides which of them is served.
 *
 * <p>Two stages, deliberately separate. Ingest checks the payload's <b>shape</b> against the
 * published contract and stores it unpublished, serving nothing. Publishing is a second, separately
 * authorised action where a human has checked the <b>content</b> is fit to be public. That split is
 * what allows a capture run in a lower environment to write into a deployed sandbox without putting
 * unreviewed data on a public endpoint.
 */
@Service
@Slf4j
public class RecordingService {

    private final RecordingRepository repository;
    private final ContractValidator contractValidator;
    private final ClockService clockService;
    private final String apiId;
    private final String operationId;

    public RecordingService(final RecordingRepository repository,
                            final ContractValidator contractValidator,
                            final ClockService clockService,
                            @Value("${recording.api-id}") final String apiId,
                            @Value("${recording.operation-id}") final String operationId) {
        this.repository = repository;
        this.contractValidator = contractValidator;
        this.clockService = clockService;
        this.apiId = apiId;
        this.operationId = operationId;
    }

    /**
     * Validates against the contract and stores unpublished.
     *
     * @param callerOid the Entra object id from the validated token — never a caller-supplied field,
     *                  or the audit trail would be whatever the caller chose to write in it
     * @throws uk.gov.hmcts.cp.scenarios.ContractViolationException if the body does not fit the contract
     */
    @Transactional
    public RecordingEntity ingest(final IngestRequest request, final String callerOid) {
        contractValidator.validate(request.httpStatus(), request.body());

        final RecordingEntity saved = repository.save(RecordingEntity.builder()
                .id(UUID.randomUUID())
                .apiId(apiId)
                .operationId(operationId)
                .caseUrn(request.caseUrn().trim())
                .httpStatus(request.httpStatus())
                .body(request.body())
                .status(RecordingStatus.UNPUBLISHED)
                .summary(request.summary())
                .recordedFrom(request.recordedFrom())
                .recordedAt(clockService.now())
                .recordedBy(callerOid)
                .build());

        log.info("Recorded {} for caseUrn:{} from:{} by:{} - unpublished",
                saved.getId(), Encode.forJava(saved.getCaseUrn()), saved.getRecordedFrom(), callerOid);
        return saved;
    }

    /**
     * Makes a recording live, standing down whatever was published for that URN before it.
     *
     * <p>The previous one is archived rather than deleted — it is the record of what consumers were
     * being shown until this moment, which is exactly what someone investigating a complaint needs.
     */
    @Transactional
    public RecordingEntity publish(final UUID id, final PublishRequest request, final String reviewerOid) {
        final RecordingEntity recording = repository.findById(id)
                .orElseThrow(() -> new RecordingNotFoundException(id));

        if (recording.getStatus() == RecordingStatus.PUBLISHED) {
            return recording;
        }

        repository.findByApiIdAndOperationIdAndCaseUrnAndStatus(
                        apiId, operationId, recording.getCaseUrn(), RecordingStatus.PUBLISHED)
                .ifPresent(superseded -> {
                    superseded.setStatus(RecordingStatus.ARCHIVED);
                    repository.save(superseded);
                    log.info("Archived {} - superseded for caseUrn:{}",
                            superseded.getId(), Encode.forJava(superseded.getCaseUrn()));
                });
        // Forces the archive UPDATE out before the publish UPDATE, so the partial unique index never
        // sees two PUBLISHED rows for the URN inside the same flush.
        repository.flush();

        recording.setStatus(RecordingStatus.PUBLISHED);
        recording.setScenarioId(request.scenarioId());
        if (request.summary() != null && !request.summary().isBlank()) {
            recording.setSummary(request.summary());
        }
        recording.setPublishedAt(clockService.now());
        recording.setPublishedBy(reviewerOid);

        final RecordingEntity published = repository.save(recording);
        log.info("Published {} as scenario:{} for caseUrn:{} by:{}",
                published.getId(), Encode.forJava(request.scenarioId()),
                Encode.forJava(published.getCaseUrn()), reviewerOid);
        return published;
    }

    /** Takes a recording out of service without deleting the evidence of what was shown. */
    @Transactional
    public RecordingEntity archive(final UUID id) {
        final RecordingEntity recording = repository.findById(id)
                .orElseThrow(() -> new RecordingNotFoundException(id));
        recording.setStatus(RecordingStatus.ARCHIVED);
        return repository.save(recording);
    }

    @Transactional(readOnly = true)
    public List<RecordingEntity> list(final RecordingStatus status) {
        return repository.findByStatusOrderByRecordedAtDesc(status);
    }

    @Transactional(readOnly = true)
    public Optional<RecordingEntity> findPublished(final String caseUrn) {
        if (caseUrn == null || caseUrn.isBlank()) {
            return Optional.empty();
        }
        return repository.findByApiIdAndOperationIdAndCaseUrnAndStatus(
                apiId, operationId, caseUrn.trim(), RecordingStatus.PUBLISHED);
    }

    @Transactional(readOnly = true)
    public List<RecordingEntity> published() {
        return repository.findByStatusOrderByRecordedAtDesc(RecordingStatus.PUBLISHED);
    }
}

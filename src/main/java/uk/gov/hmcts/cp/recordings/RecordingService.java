package uk.gov.hmcts.cp.recordings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.entity.RecordingEntity;
import uk.gov.hmcts.cp.entity.RecordingStatus;
import uk.gov.hmcts.cp.repository.RecordingRepository;
import uk.gov.hmcts.cp.scenarios.ContractValidator;
import uk.gov.hmcts.cp.services.ClockService;

import java.util.List;
import java.util.Optional;

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

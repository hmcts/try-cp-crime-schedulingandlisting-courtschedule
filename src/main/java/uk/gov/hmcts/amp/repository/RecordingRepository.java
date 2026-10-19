package uk.gov.hmcts.amp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.amp.entity.RecordingEntity;
import uk.gov.hmcts.amp.entity.RecordingStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordingRepository extends JpaRepository<RecordingEntity, UUID> {

    List<RecordingEntity> findByStatusOrderByRecordedAtDesc(RecordingStatus status);

    Optional<RecordingEntity> findByApiIdAndOperationIdAndCaseUrnAndStatus(
            String apiId, String operationId, String caseUrn, RecordingStatus status);

    boolean existsByCaseUrnAndStatus(String caseUrn, RecordingStatus status);

    long countByStatus(RecordingStatus status);
}

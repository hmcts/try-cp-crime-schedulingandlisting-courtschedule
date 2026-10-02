package uk.gov.hmcts.cp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.cp.entity.RecordingEntity;
import uk.gov.hmcts.cp.entity.RecordingStatus;

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

package uk.gov.hmcts.cp.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.entity.RecordingEntity;
import uk.gov.hmcts.cp.entity.RecordingStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the two things about the store that everything else rests on: that a body survives a
 * {@code jsonb} round trip byte for byte, and that only one recording per URN can be published.
 */
// The admin realm is off here deliberately: this class exercises the store, and an unconfigured
// admin realm refuses to start the application rather than running unguarded. That refusal is the
// behaviour we want in production, so it is left in place and switched off explicitly instead.
@SpringBootTest(properties = "admin.auth.disabled=true")
@Testcontainers
class RecordingRepositoryTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private RecordingRepository repository;

    private static final String BODY = """
            {"courtSchedule":[{"hearings":[{"hearingId":"HRG-1","hearingType":"Trial"}]}]}""";

    private RecordingEntity recording(final String caseUrn, final RecordingStatus status) {
        return RecordingEntity.builder()
                .id(UUID.randomUUID())
                .apiId("api-cp-crime-schedulingandlisting-courtschedule")
                .operationId("getCourtScheduleByCaseUrn")
                .caseUrn(caseUrn)
                .httpStatus(200)
                .body(BODY)
                .status(status)
                .recordedFrom("dev")
                .recordedAt(Instant.now())
                .recordedBy("00000000-0000-0000-0000-0000000000ff")
                .build();
    }

    @Test
    @DisplayName("a body survives the round trip byte for byte")
    void jsonbRoundTripsExactly() {
        final UUID id = repository.saveAndFlush(recording("TIN-RT-01", RecordingStatus.PUBLISHED)).getId();
        repository.flush();

        final RecordingEntity read = repository.findById(id).orElseThrow();

        // Byte-for-byte, which is why the column is `json` and not `jsonb`. jsonb normalises
        // whitespace and reorders keys, so an approved example would come back altered - proven by
        // this assertion failing against jsonb before the column type was changed.
        assertThat(read.getBody()).isEqualTo(BODY);
        assertThat(read.getRecordedFrom()).isEqualTo("dev");
        assertThat(read.getStatus()).isEqualTo(RecordingStatus.PUBLISHED);
    }

    @Test
    @DisplayName("only one recording per case URN may be published at a time")
    void onlyOnePublishedPerCaseUrn() {
        repository.saveAndFlush(recording("TIN-DUP-01", RecordingStatus.PUBLISHED));

        assertThatThrownBy(() -> repository.saveAndFlush(recording("TIN-DUP-01", RecordingStatus.PUBLISHED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("unpublished rows may pile up against the same URN")
    void unpublishedMayRepeat() {
        repository.saveAndFlush(recording("TIN-MANY-01", RecordingStatus.UNPUBLISHED));
        repository.saveAndFlush(recording("TIN-MANY-01", RecordingStatus.UNPUBLISHED));
        repository.saveAndFlush(recording("TIN-MANY-01", RecordingStatus.PUBLISHED));

        // Recapturing the same scenario repeatedly is the normal case - only the approved one serves.
        assertThat(repository.findByStatusOrderByRecordedAtDesc(RecordingStatus.UNPUBLISHED)).hasSize(2);
        assertThat(repository.existsByCaseUrnAndStatus("TIN-MANY-01", RecordingStatus.PUBLISHED)).isTrue();
    }
}

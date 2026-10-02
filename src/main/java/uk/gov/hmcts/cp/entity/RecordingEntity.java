package uk.gov.hmcts.cp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

/**
 * A captured example response.
 *
 * <p>The body is held as the JSON text that was validated, not as a mapped object graph. Storing
 * the bytes that passed the contract check means what is served is exactly what was recorded and
 * approved — re-mapping it through the model on the way out would let a later change to the
 * generated classes silently alter an already-published example.
 */
@Entity
@Table(name = "recording")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class RecordingEntity {

    @Id
    private UUID id;

    @Column(name = "api_id", nullable = false)
    private String apiId;

    @Column(name = "operation_id", nullable = false)
    private String operationId;

    @Column(name = "case_urn", nullable = false)
    private String caseUrn;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    /**
     * The exact JSON that passed the ingest gate.
     *
     * <p>Mapped as a plain {@code String} with a cast on write, not with {@code @JdbcTypeCode(JSON)}.
     * Hibernate's JSON type runs the value back through Jackson and reformats it, and a {@code jsonb}
     * column reparses it again — both were observed doing exactly that before this mapping was
     * settled, and the byte-for-byte assertion in {@code RecordingRepositoryTest} is what holds it
     * in place.
     *
     * <p>It matters because this is the text a <b>reviewer reads</b> when deciding whether the
     * capture is fit to be public. If the store rewrote it, the reviewer would be approving
     * something other than what was captured. The response finally served to a consumer is this
     * text re-materialised through the generated model, which is what makes the endpoint provably
     * contract-conformant — semantically the same document, canonically formatted.
     */
    @ColumnTransformer(write = "?::json")
    @Column(name = "body", nullable = false, columnDefinition = "json")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RecordingStatus status;

    @Column(name = "scenario_id")
    private String scenarioId;

    @Column(name = "summary")
    private String summary;

    /**
     * Source environment: {@code dev}, {@code ste}, {@code sit}, or {@code fixture} for a seed.
     */
    @Column(name = "recorded_from", nullable = false)
    private String recordedFrom;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** The capturing caller's Entra object id, taken from the validated token — never caller-supplied. */
    @Column(name = "recorded_by", nullable = false)
    private String recordedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** The reviewer's Entra object id, taken from the validated token. */
    @Column(name = "published_by")
    private String publishedBy;
}

package uk.gov.hmcts.cp.scenarios;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.entity.RecordingEntity;
import uk.gov.hmcts.cp.recordings.RecordingService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the sandbox will serve, keyed by case URN.
 *
 * <p>A read-through cache over the published recordings rather than the source of truth — the store
 * is. Held in memory because every consumer request needs it and it changes only when someone
 * publishes, and refreshed explicitly at that moment rather than on a timer, so a publish is live
 * immediately without a redeploy.
 *
 * <p>Replaced on refresh rather than mutated, so a request reading the catalogue mid-publish sees
 * the old map in full instead of a half-updated one.
 */
@Service
@Slf4j
public class ScenarioCatalog {

    private final RecordingService recordingService;
    private final ContractValidator contractValidator;
    private final AtomicReference<Map<String, Scenario>> cache = new AtomicReference<>(Map.of());

    public ScenarioCatalog(final RecordingService recordingService, final ContractValidator contractValidator) {
        this.recordingService = recordingService;
        this.contractValidator = contractValidator;
    }

    /** Case-insensitive so a consumer retyping a URN from the catalogue is not tripped up by case. */
    public Optional<Scenario> findByCaseUrn(final String caseUrn) {
        if (caseUrn == null || caseUrn.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get().get(key(caseUrn)));
    }

    public List<Scenario> all() {
        return List.copyOf(cache.get().values());
    }

    /** Rebuilds the cache from the store. Called at startup and after every publish. */
    public void refresh() {
        final Map<String, Scenario> rebuilt = new LinkedHashMap<>();
        for (final RecordingEntity recording : recordingService.published()) {
            rebuilt.put(key(recording.getCaseUrn()), materialise(recording));
        }
        cache.set(Map.copyOf(rebuilt));
        log.info("Scenario catalogue refreshed: {} published scenarios {}", rebuilt.size(), rebuilt.keySet());
    }

    private Scenario materialise(final RecordingEntity recording) {
        final boolean success = ContractValidator.isSuccess(recording.getHttpStatus());
        return new Scenario(
                recording.getScenarioId(),
                recording.getCaseUrn(),
                recording.getHttpStatus(),
                recording.getSummary(),
                recording.getRecordedFrom(),
                success ? contractValidator.parseSchedule(recording.getBody()) : null,
                success ? null : contractValidator.parseError(recording.getBody()));
    }

    private static String key(final String caseUrn) {
        return caseUrn.trim().toLowerCase(Locale.ROOT);
    }
}

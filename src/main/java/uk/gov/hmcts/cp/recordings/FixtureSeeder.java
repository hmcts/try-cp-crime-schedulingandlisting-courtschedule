package uk.gov.hmcts.cp.recordings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.entity.RecordingEntity;
import uk.gov.hmcts.cp.entity.RecordingStatus;
import uk.gov.hmcts.cp.repository.RecordingRepository;
import uk.gov.hmcts.cp.scenarios.Fixture;
import uk.gov.hmcts.cp.scenarios.FixtureLoader;
import uk.gov.hmcts.cp.scenarios.ScenarioCatalog;
import uk.gov.hmcts.cp.services.ClockService;

import java.util.UUID;

/**
 * Seeds the committed fixtures at startup, then warms the catalogue.
 *
 * <p>Fixtures are seeded from Java rather than from a Flyway migration on purpose. A migration is
 * immutable once applied, whereas a fixture is meant to be edited — putting them in one would mean
 * every correction needed a new migration, and environments would diverge depending on when they
 * were first deployed.
 *
 * <p>Only URNs with nothing published are seeded. A recording that has superseded a fixture is never
 * reverted by a restart, which is what lets the two coexist: fixtures are the floor, recordings are
 * the improvement on it.
 */
@Component
@Slf4j
public class FixtureSeeder implements ApplicationRunner {

    /** Seeded rows are attributed to nobody, and say so rather than borrowing a real identity. */
    private static final String SYSTEM_OID = "00000000-0000-0000-0000-000000000000";
    private static final String FIXTURE_SOURCE = "fixture";

    private final FixtureLoader fixtureLoader;
    private final RecordingRepository repository;
    private final ScenarioCatalog scenarioCatalog;
    private final ClockService clockService;
    private final String apiId;
    private final String operationId;
    private final boolean seedEnabled;

    public FixtureSeeder(final FixtureLoader fixtureLoader,
                         final RecordingRepository repository,
                         final ScenarioCatalog scenarioCatalog,
                         final ClockService clockService,
                         @Value("${recording.api-id}") final String apiId,
                         @Value("${recording.operation-id}") final String operationId,
                         @Value("${recording.seed-fixtures:true}") final boolean seedEnabled) {
        this.fixtureLoader = fixtureLoader;
        this.repository = repository;
        this.scenarioCatalog = scenarioCatalog;
        this.clockService = clockService;
        this.apiId = apiId;
        this.operationId = operationId;
        this.seedEnabled = seedEnabled;
    }

    @Override
    @Transactional
    public void run(final ApplicationArguments args) {
        if (seedEnabled) {
            seed();
        } else {
            log.info("Fixture seeding disabled - serving only what is in the store");
        }
        scenarioCatalog.refresh();
    }

    private void seed() {
        int seeded = 0;
        for (final Fixture fixture : fixtureLoader.load()) {
            if (repository.existsByCaseUrnAndStatus(fixture.caseUrn(), RecordingStatus.PUBLISHED)) {
                continue;
            }
            repository.save(RecordingEntity.builder()
                    .id(UUID.randomUUID())
                    .apiId(apiId)
                    .operationId(operationId)
                    .caseUrn(fixture.caseUrn())
                    .httpStatus(fixture.httpStatus())
                    .body(fixture.body())
                    .status(RecordingStatus.PUBLISHED)
                    .scenarioId(fixture.id())
                    .summary(fixture.summary())
                    .recordedFrom(FIXTURE_SOURCE)
                    .recordedAt(clockService.now())
                    .recordedBy(SYSTEM_OID)
                    .publishedAt(clockService.now())
                    .publishedBy(SYSTEM_OID)
                    .build());
            seeded++;
        }
        log.info("Seeded {} committed fixtures", seeded);
    }
}

package uk.gov.hmcts.cp.services;

import java.time.Clock;
import java.time.Instant;

public class ClockService {

    private final Clock clock;

    public ClockService(final Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }
}

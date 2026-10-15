package uk.gov.hmcts.cp.recordings;

import java.util.UUID;

/** No recording with that id. Mapped to 404 by the exception handler. */
public class RecordingNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RecordingNotFoundException(final UUID id) {
        super("No recording with id " + id);
    }
}

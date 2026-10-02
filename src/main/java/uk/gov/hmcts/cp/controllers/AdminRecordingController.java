package uk.gov.hmcts.cp.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.auth.AdminTokenValidator;
import uk.gov.hmcts.cp.auth.TokenValidationException;
import uk.gov.hmcts.cp.auth.ValidatedCaller;
import uk.gov.hmcts.cp.domain.IngestRequest;
import uk.gov.hmcts.cp.domain.PublishRequest;
import uk.gov.hmcts.cp.domain.RecordingResponse;
import uk.gov.hmcts.cp.entity.RecordingStatus;
import uk.gov.hmcts.cp.filters.AdminAuthFilter;
import uk.gov.hmcts.cp.recordings.RecordingService;
import uk.gov.hmcts.cp.scenarios.ScenarioCatalog;

import java.util.List;
import java.util.UUID;

/**
 * Ingest and publish.
 *
 * <p>Every path here is authenticated by {@link AdminAuthFilter} before it is reached — with a real
 * corporate Entra token, never the sandbox's published demo credentials. The role check is made
 * per-operation rather than once at the filter, because recording and approving are deliberately
 * different permissions: capture is automated and frequent, publishing is what puts data in front
 * of the public.
 */
@RestController
@RequestMapping("/admin/recordings")
@Slf4j
public class AdminRecordingController {

    private final RecordingService recordingService;
    private final AdminTokenValidator adminTokenValidator;
    private final ScenarioCatalog scenarioCatalog;

    public AdminRecordingController(final RecordingService recordingService,
                                    final AdminTokenValidator adminTokenValidator,
                                    final ScenarioCatalog scenarioCatalog) {
        this.recordingService = recordingService;
        this.adminTokenValidator = adminTokenValidator;
        this.scenarioCatalog = scenarioCatalog;
    }

    /** Stores a capture, unpublished. Serves nothing until someone publishes it. */
    @PostMapping
    public ResponseEntity<RecordingResponse> ingest(@Valid @RequestBody final IngestRequest request,
                                                    final HttpServletRequest httpRequest)
            throws TokenValidationException {

        final ValidatedCaller caller = caller(httpRequest);
        adminTokenValidator.assertMayRecord(caller);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RecordingResponse.from(recordingService.ingest(request, caller.clientId().toString())));
    }

    /** Everything at a given status, newest first. Defaults to what is awaiting review. */
    @GetMapping
    public ResponseEntity<List<RecordingResponse>> list(
            @RequestParam(name = "status", defaultValue = "UNPUBLISHED") final RecordingStatus status,
            final HttpServletRequest httpRequest) throws TokenValidationException {

        adminTokenValidator.assertMayRecord(caller(httpRequest));
        return ResponseEntity.ok(recordingService.list(status).stream().map(RecordingResponse::from).toList());
    }

    /** Makes a recording live, and refreshes the catalogue so it serves immediately. */
    @PostMapping("/{id}/publish")
    public ResponseEntity<RecordingResponse> publish(@PathVariable final UUID id,
                                                     @Valid @RequestBody final PublishRequest request,
                                                     final HttpServletRequest httpRequest)
            throws TokenValidationException {

        final ValidatedCaller caller = caller(httpRequest);
        adminTokenValidator.assertMayPublish(caller);

        final RecordingResponse published = RecordingResponse.from(
                recordingService.publish(id, request, caller.clientId().toString()));
        scenarioCatalog.refresh();
        return ResponseEntity.ok(published);
    }

    /** Takes a recording out of service. Also a publisher's decision, so it needs that role. */
    @PostMapping("/{id}/archive")
    public ResponseEntity<RecordingResponse> archive(@PathVariable final UUID id,
                                                     final HttpServletRequest httpRequest)
            throws TokenValidationException {

        adminTokenValidator.assertMayPublish(caller(httpRequest));
        final RecordingResponse archived = RecordingResponse.from(recordingService.archive(id));
        scenarioCatalog.refresh();
        return ResponseEntity.ok(archived);
    }

    /**
     * The caller the filter already validated. Never read from the request body — an audit trail the
     * caller can write for itself records nothing.
     */
    private static ValidatedCaller caller(final HttpServletRequest request) {
        final Object attribute = request.getAttribute(AdminAuthFilter.CALLER_ATTRIBUTE);
        if (attribute instanceof ValidatedCaller validated) {
            return validated;
        }
        // Unreachable while AdminAuthFilter guards every path under /admin. If it ever is reached,
        // the filter has been unwired and this must fail closed rather than proceed anonymously.
        throw new IllegalStateException("No validated admin caller on the request - AdminAuthFilter missing?");
    }
}

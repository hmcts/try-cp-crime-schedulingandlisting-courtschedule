package uk.gov.hmcts.cp.entity;

/**
 * Lifecycle of a captured example.
 *
 * <p>Nothing is served until it is {@link #PUBLISHED}. That split is what lets a capture run write
 * into a production sandbox without putting unreviewed data on a public endpoint: ingest checks the
 * payload's <i>shape</i>, and publishing is where a human checks its <i>content</i>.
 */
public enum RecordingStatus {

    /** Stored and validated against the contract, but not served to anyone. */
    UNPUBLISHED,

    /** Served for its case URN. At most one per URN per operation, enforced by a partial index. */
    PUBLISHED,

    /** Superseded or rejected. Retained for provenance, never served. */
    ARCHIVED
}

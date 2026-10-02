-- One row per captured example.
--
-- The body is `json`, deliberately, not `jsonb`. jsonb reparses and reserialises the document -
-- it normalises whitespace, reorders keys and drops duplicates - so a body read back from jsonb
-- is not the text that was validated and approved. Nothing here queries inside a body, so jsonb's
-- indexing and containment operators buy nothing, while its rewriting would quietly undermine the
-- one guarantee this table exists to provide. `json` still rejects malformed JSON at the database.
create table recording (
    id             uuid        primary key,
    api_id         text        not null,   -- api-cp-crime-schedulingandlisting-courtschedule
    operation_id   text        not null,   -- getCourtScheduleByCaseUrn
    case_urn       text        not null,   -- the key consumers call with
    http_status    int         not null,
    body           json        not null,
    status         text        not null,   -- UNPUBLISHED | PUBLISHED | ARCHIVED
    scenario_id    text,                   -- set at publish, e.g. allocated
    summary        text,                   -- shown on GET /scenarios
    recorded_from  text        not null,   -- dev | ste | sit, or 'fixture' for committed seeds
    recorded_at    timestamptz not null,
    recorded_by    text        not null,   -- caller's Entra oid, from the validated token
    published_at   timestamptz,
    published_by   text                    -- reviewer's Entra oid
);

-- At most one PUBLISHED recording per URN per operation. A partial index rather than a plain
-- unique constraint, so superseded and unpublished rows can accumulate against the same URN
-- while only one of them is ever served.
create unique index recording_published_urn_idx
    on recording (api_id, operation_id, case_urn)
    where status = 'PUBLISHED';

create index recording_status_idx on recording (status);

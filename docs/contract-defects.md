# Contract defects this work surfaced

All of these are in
[`api-cp-crime-schedulingandlisting-courtschedule`](https://github.com/hmcts/api-cp-crime-schedulingandlisting-courtschedule)
and worth fixing there. Under the recording design they stop being documentation issues and start
blocking ingest — see [admin-flow.md](admin-flow.md).

This repo pins **1.1.2**.

## 1. The spec's `examples` do not match its own schema

`CourtScheduleResponse` is `{ courtSchedule: [ { hearings: [...] } ] }`, but both response examples
are shaped `{ hearings: { ... } }` — no `courtSchedule` wrapper, and `hearings` as an object. A stub
built from those examples deserialises to an empty response. The fixtures here follow the **schema**.
The wrong shape is baked into the published jar's `@ExampleObject` annotations too.

The guard that would have caught this is switched off: `oas3-valid-media-example` and
`oas3-valid-schema-example` are set to `off` in `.spectral.yml` across all six API repos.

## 2. The live service returns hearing fields the contract does not define

The AMS handover pack §5.1 documents `courtHouse` and `lastUpdatedTime` on each hearing.

**`lastUpdatedTime` — fixed in 1.1.2.** It is now declared on `Hearing`. That is the whole of the
difference between 1.1.0 and 1.1.2; every other model is byte-for-byte the same.

**`courtHouse` on a hearing — still open.** `courtHouse` is declared on `CourtSitting` and on
`HearingWeekCommencing`, never on `Hearing` itself. The fixtures here carry it at the sitting level,
where the schema puts it, which is why they validate. A real recording that carries it at the hearing
level, as the handover pack describes, is still rejected at ingest.

That is what `FixtureLoaderTest.undeclaredFieldIsRejected` and
`RecordingJourneyTest.contractViolationIsRejectedAtIngest` now probe with. If a later contract version
declares `courtHouse` on `Hearing` too, those tests go green for the wrong reason — pick another
undeclared field rather than deleting them.

## 3. `listNote` can never be returned as null

The schema says it "will always be required but can be null", and the handover pack's healthy-response
check for `/slc` reads "`listNote` is always present but may be `null`". Both are unachievable: the
generator config applies `@JsonInclude(NON_NULL)` to every model, so a null `listNote` is omitted from
the JSON entirely. Verified here by serving a fixture with `"listNote": null` and observing the field
absent. **This applies to the live service too** — it uses the same generated models.

## 4. No `securitySchemes`, so Swagger UI has no Authorize button

The spec declares none. Adding a `clientCredentials` scheme pointing `tokenUrl` at this sandbox's
token endpoint is what would make "Try it out" usable end to end from the public catalogue.

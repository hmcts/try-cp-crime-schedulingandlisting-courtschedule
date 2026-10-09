## Repo: try-cp-crime-schedulingandlisting-courtschedule

Sandbox ("Try It Now") implementation of the Scheduling and Listing Court Schedule API. Serves
contract-checked examples — committed fixtures as the floor, recordings captured from a lower
environment on top — so a prospective consumer can call the API before requesting access to the
live one.

**Pattern**: Recording store with real token validation, two auth realms
**Platform**: CNP (Jenkins + Flux), product `amp`, component `try-slc`, **demo is the last
environment** — **not** the CP
platform. No ADO pipelines, no `cp-vp-aks-deploy`, no `wire-service-deployment`.
**Implements**: `api-cp-crime-schedulingandlisting-courtschedule` (pinned `1.1.0`)
**Backend dependencies**: Postgres only. No route to the Common Platform, and must not gain one.

## Source Structure

```
uk.gov.hmcts.cp/
  Application.java                    @SpringBootApplication
  auth/                               COPIED from service-cp-crime-scheduleandlist-courtschedule
    EntraTokenValidator               Signature (RS256-pinned) + claims validation — do not edit
    EntraAuthProperties               @Value auth.*; fails startup on invalid config
    AuthorizationPolicy               MODIFIED: actuator + demo endpoints exempt; /admin delegated
    AdminAuthProperties               Real corporate Entra; fails startup unless configured or disabled
    AdminTokenValidator               Wraps an EntraTokenValidator - not a second bean of that type
    AuthMode, ValidatedCaller, TokenValidationException
  config/
    AppConfig                         ImmutableJWKSet (NOT JWKSourceBuilder), validator, ClockService
    CorsConfig                        Allows the catalogue origin; Try-it-out is a browser fetch
  controllers/
    CourtScheduleController           Implements generated CourtScheduleApi; reads from ScenarioCatalog
    AdminRecordingController          Ingest, list, publish, archive - per-operation role checks
    DemoTokenController               POST /oauth2/v2.0/token — client_credentials, Entra-shaped
    JwksController                    GET /.well-known/jwks.json — public key only
    ScenarioController                GET /scenarios — self-documenting, unauthenticated
    RootController, GlobalExceptionHandler
  demo/
    DemoSigningKey                    RSA keypair from vault; ephemeral fallback warns loudly
    DemoTokenMinter                   Claims dictated by EntraTokenValidator, not by choice
    DemoClientRegistry, DemoClient    Two clients: one entitled, one not
  filters/
    AdminAuthFilter                   Guards /admin/** at order +4, BEFORE the demo filter at +5
    ClientIdResolutionFilter          COPIED; MODIFIED to exempt CORS preflight
    tracing/TracingFilter             COPIED; MODIFIED to generate traceId when absent
  scenarios/
    ContractValidator                 THE gate. Used by BOTH the fixture loader and ingest
    FixtureLoader                     Reads + validates committed stubs, no DB - the build-time gate
    ScenarioCatalog                   Read-through cache over published recordings; refreshed on publish
    Scenario, Fixture, ScenarioDefinition, ContractViolationException, ScenarioFailureException
  recordings/
    RecordingService                  Ingest (validate + store unpublished), publish, archive
    FixtureSeeder                     ApplicationRunner; seeds fixtures for URNs with nothing published
  entity/ repository/                 RecordingEntity, RecordingStatus, RecordingRepository
  domain/                             IngestRequest, PublishRequest, RecordingResponse
  services/
    ClockService, ErrorResponseFactory
```

## Architecture Rules

- **Never add a backend client.** The value of this service is that it cannot reach CP. A real call
  would make responses non-deterministic and drag it back inside the CP network boundary.
- **Demo is the last environment. There is no production deployment and there must not be one.**
  Sandbox, preview, AAT, demo. Anyone adding a prod overlay, a prod tfvars or an
  `environment-approvals.yml` entry for this component has misread the design. Demo is public and
  needs no VPN, so "only demo" is not a reason to relax anything below.
- **Data flows inbound, from non-live CP only.** Examples are captured from `service-cp-*` in SIT,
  dev (`devamp01`) or STE and **pushed** into the **demo** deployment in CNP over
  `/admin/recordings`. The demo service never calls out, and the live CP estate is never a source.
  There is no CP-to-CNP network path; what crosses is an authenticated HTTPS client calling a public
  host, and the admin Entra token is the whole of the control. Changing either half of that — making
  this service fetch, or recording from live — breaks the reason it is allowed to run in CNP and be
  publicly readable.
- **The auth code is a copy, not a fork.** `EntraTokenValidator`, `EntraAuthProperties`, `AuthMode`,
  `ValidatedCaller` and `TokenValidationException` must stay **byte-identical** to
  `service-cp-crime-scheduleandlist-courtschedule` so consumers meet production auth behaviour here;
  `diff -r` against that repo is the check. `AuthorizationPolicy` (exempt paths),
  `ClientIdResolutionFilter` (CORS preflight) and `TracingFilter` (traceId generation) diverge
  deliberately, each documented in-file.
- **Auth is real, not mocked.** `AUTH_MODE=ENFORCE`. The only change is the key *source*:
  `ImmutableJWKSet` instead of a JWKS fetch. Never set `AUTH_MODE=OFF` — it would delete the
  behaviour this service exists to demonstrate.
- **Two auth realms, and they must not be confused.** The demo realm (self-issued, credentials
  published in the catalogue) guards the public read API. The admin realm (`AdminAuthFilter`, real
  corporate Entra) guards `/admin/**`. `AuthorizationPolicy.isExemptFromValidation` returns true for
  `/admin` — that does **not** mean public, it means the admin filter at a lower order already
  handled it. Removing `AdminAuthFilter` without removing that would leave ingest wide open.
- **The body column is `json`, not `jsonb`, and is mapped as a plain `String`.** Both `jsonb` and
  Hibernate's `@JdbcTypeCode(JSON)` rewrite the document, so a reviewer would approve something
  other than what was captured. `RecordingRepositoryTest` asserts the round trip byte-for-byte —
  that test failing means someone reintroduced one of them.
- **Fixtures seed, recordings supersede.** Seeding only fills URNs with nothing published, so a
  restart never reverts a published recording.
- **Stubs go through the generated model.** `ScenarioCatalog` deserialises with
  `FAIL_ON_UNKNOWN_PROPERTIES`; responses are re-serialised from `CourtScheduleResponse`. Never serve
  raw JSON straight through — that is the one guarantee a WireMock/Prism deployment cannot make.
- **Demo tokens must stay worthless elsewhere.** `iss`/`aud`/`tid` are this service's own. Never
  point `auth.*` at a real Entra tenant.

## Adding a scenario

Two routes, one gate. A **fixture**: body into `src/main/resources/stubs/`, entry into
`stubs/scenarios.yaml` — a body that does not match the contract fails `FixtureLoaderTest` and fails
startup. A **recording**: `POST /admin/recordings`, which runs the same `ContractValidator` and
answers 400 naming the offending field. Recordings serve nothing until published.

## Debugging

| Symptom | Cause / Fix |
|---|---|
| Pod CrashLoopBackOff, probes 401 | `/health`,`/info`,`/prometheus` dropped from `PUBLIC_EXACT_PATHS`. With `base-path: /` the prefix rule cannot match them — see the comment in `AuthorizationPolicy` |
| Try-it-out fails in browser, curl works | CORS. Check `demo.allowed-origins`, and that preflight is exempt in `ClientIdResolutionFilter` |
| Try-it-out fails at DNS | Non-prod hosts are internal. Prod is a `platform.hmcts.net` host behind Front Door — see Deployment in README |
| Intermittent 401 after a deploy | `DEMO_SIGNING_KEY_JWK` not set, so each pod generates its own key. The pipeline creates it after Terraform via `bin/ensure-demo-signing-key.sh`; if it is missing, that hook did not run or the vault name is wrong |
| Pods stuck `FailedMount` on `try-slc-DEMO-SIGNING-KEY-JWK` | The CSI driver fails the **whole** mount if any listed secret is absent, so helm waits until the 40-minute `Install Charts to AKS` timeout. Terraform creates the `POSTGRES-*` secrets but not this one — `bin/ensure-demo-signing-key.sh` does, from a `buildinfra:<env>` hook |
| `/info` answers `{}` locally | Expected. The CNP pipeline writes `build-info.properties` into `src/main/resources/META-INF` before `assemble`; a local build has no such file. Only pipeline-built artefacts carry one |
| `processResources` fails: *Entry META-INF/build-info.properties is a duplicate* | Something added a `springBoot.buildInfo` block. The pipeline already generates that file, so Gradle generating a second one collides. **Do not fix this with `duplicatesStrategy`** — the generated file then wins and the pipeline's `build.commit` and `build.number` are lost. Remove the block; see the note in `build.gradle` |
| `bootJar` fails: *Could not find org.openapitools:openapi-generator-core* | `api-cp-*` publishes the OpenAPI generator, `swagger-parser` and `swagger-annotations` in its **runtimeElements** variant, though all three are build-time only. `apiElements` declares none of them, so `compileJava` passes and `bootJar` — the first task to resolve `runtimeClasspath` — is where it breaks. They are excluded in `build.gradle`; **do not re-add**. Green locally, red on Jenkins, because two of the coordinates are not resolvable on the CNP agent |
| Configuration fails: *Could not resolve io.github.ben-manes:gradle-versions-plugin* | That plugin was removed. Its artefact is not on Maven Central - only the Gradle Plugin Portal, which serves downloads from a separate host (`plugins-artifacts.gradle.org`), so it is fragile on a CNP agent. Nothing ran `dependencyUpdates`; Renovate covers it. **Do not re-add it** - see the note in `build.gradle` |
| Jenkins logs `repository "<product>/try-slc" is not found` during Checkout | Benign. `Acr.hasRepoTag` runs `az acr repository show-tags` only to decide whether an image rebuild can be skipped, and swallows the failure (`Acr.groovy:626-631`). Jenkins prints the step failure before Groovy catches it, so it looks fatal. Expected until the first build pushes an image, and again after any product rename |
| Startup fails naming a stub file | That stub drifted from the contract, or `build.gradle` pinned a new contract version |
| Startup fails on `admin.auth.tenant-id` | The admin realm is half-configured. Set `ADMIN_DISABLED=true` locally, or supply a real tenant and audience. It fails closed on purpose |
| `/admin/**` answers 503 | Admin realm disabled — not a bug, the configured state when no Entra app registration exists |
| Ingest answers 400 naming a field | The contract gate working. Fix the spec or the capture, not the gate |
| 403 where 200 expected | Using the unentitled demo client (`2222...`). That is the scenario working |

## Repo-Specific Notes

- Three defects in the api-cp spec were found while building this and are **not** fixed here: the
  response `examples` do not match the schema; the live service returns `courtHouse` /
  `lastUpdatedTime` on a hearing which the `Hearing` schema does not define; and `listNote` can never
  be serialised as null because the generator applies `@JsonInclude(NON_NULL)`, contradicting both the
  schema and the handover pack's healthy-response check. Fixtures follow the schema. See README.
- Because of `NON_NULL`, never write `"field": null` into a stub expecting it to appear in the
  response — it is dropped. Use a real value or omit the field.
- The spec declares no `securitySchemes`, so Swagger UI shows no Authorize button.
- Remaining CNP onboarding steps are in `CNP-ONBOARDING-PLAN.md`. The two that block the recording
  pipeline are the Postgres Terraform module and the admin Entra app registration; until the latter
  exists, run with `ADMIN_DISABLED=true`.

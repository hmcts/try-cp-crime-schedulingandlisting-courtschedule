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
    TokenKeySource                    SELF_ISSUED | ENTRA - which realm, and therefore who issued the token
    AuthKeySourceProperties           auth.key-source + the ENTRA startup check; NOT part of the copied set
    AppConfig                         Picks ImmutableJWKSet or JWKSourceBuilder, validator, ClockService
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
- **Auth is real, not mocked.** `AUTH_MODE=ENFORCE`. The only thing that varies is the key *source*,
  via `auth.key-source` (`uk.gov.hmcts.cp.config.TokenKeySource`): `ImmutableJWKSet` over the demo key
  (`SELF_ISSUED`) or a `JWKSourceBuilder` fetching the tenant's JWKS (`ENTRA`). Never set
  `AUTH_MODE=OFF` — it would delete the behaviour this service exists to demonstrate.
- **Two auth realms, and they must not be confused.** *(Not on master: the admin half was removed in
  PR #36 and is restored by PR #37. Kept here because it applies the moment that lands.)* The demo
  realm (self-issued, credentials published in the catalogue) guards the public read API. The admin realm (`AdminAuthFilter`, real
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
- **The key source and the minter must agree, and that is load-bearing.** `DemoTokenMinter` reads the
  same `EntraAuthProperties` the validator does, so pointing `auth.*` at a real tenant while minting
  is enabled would stamp Microsoft's issuer on a token signed with the demo key — a token nothing can
  verify, advertised by `/scenarios` as the way in. So `auth.key-source=ENTRA` removes
  `DemoSigningKey`, `DemoTokenMinter`, `DemoClientRegistry`, `DemoTokenController` and
  `JwksController` from the context via `@ConditionalOnProperty`, and `AuthKeySourceProperties`
  refuses to start if `auth.*` still holds the `demo.self-issued.*` values. Keep `demo.self-issued.*`
  as the single source of those identifiers — it is what the startup check compares against. In
  `SELF_ISSUED`, demo tokens stay worthless elsewhere because `iss`/`aud`/`tid` are this service's own.
- **`ENTRA` is the default.** An environment nobody configured fails startup rather than accepting
  tokens the service minted itself. Local development sets `AUTH_KEY_SOURCE=SELF_ISSUED` in `.env`;
  the two `@SpringBootTest` classes set it as a test property.
- **One tenant for every `ENTRA` environment: `hmctsextsbox`** (`d44f885c-…`, audience
  `30288840-…`, the `hmcts-api-marketplace` registration that defines `app.read`). Not a try-slc
  registration, and not `api-cp`'s `aat.tfvars`/`prod.tfvars` tenants: this is where
  `service-api-marketplace` and `web-api-marketplace` register a consumer's application and grant it
  the role, so it is the only tenant whose tokens a developer can actually obtain. Sandbox and AAT
  use it; nothing else does.
- **Preview and the AAT staging slot are `SELF_ISSUED`, so CI can prove the 200 path.** Both exist
  only to run the Jenkins test stages, and those stages hold no Entra client secret — against an
  `ENTRA` deployment the functional suite can assert the forged-token 401 and nothing more, and that
  401 is indistinguishable from a blocked JWKS fetch. Self-issued, it mints a token and walks the
  whole journey. `ENTRA` is verified against sandbox, where a real marketplace registration exists.
  Do not flip these two to `ENTRA` to "match sandbox": the realms are per-environment by design, and
  the thing a pre-merge stage can actually verify is the demo journey.
- **Demo is `SELF_ISSUED`, and that is a decision with a reason.** It keeps the published demo
  credentials working, which is what the public catalogue's Try-it-out needs: a browser cannot
  complete a `client_credentials` exchange against Entra, so an Entra demo has no browser journey.
  Which tenant it should eventually validate against is unsettled. If it ever moves to the
  production tenant, note the consequence — a token valid against the **production** Court Schedule
  API becomes valid here, which is safe in this direction only (no CP data, no route to CP, no write
  surface) and must never be made true in reverse.

## Adding a scenario

Two routes, one gate. A **fixture**: body into `src/main/resources/stubs/`, entry into
`stubs/scenarios.yaml` — a body that does not match the contract fails `FixtureLoaderTest` and fails
startup. A **recording**: `POST /admin/recordings`, which runs the same `ContractValidator` and
answers 400 naming the offending field. Recordings serve nothing until published.

## Debugging

| Symptom | Cause / Fix |
|---|---|
| Pod CrashLoopBackOff, probes 401 | `/info`/`/prometheus` dropped from `PUBLIC_EXACT_PATHS`, or `isHealthPath` removed. With `base-path: /` the inherited prefix rule cannot match them — see the comments in `AuthorizationPolicy`. `/health` and its groups are a rule, not an enumeration, so a **new health group** is covered automatically; `/healthz` and `/health-admin` are deliberately **not** |
| Build fails `No test report files were found. Configuration error?` | A test task ran `NO-SOURCE`. `GradleBuilder.smokeTest`/`functionalTest` call Jenkins' `junit` step with **no** `allowEmptyResults`, so an empty `src/smokeTest` or `src/functionalTest` fails the build after a successful deploy. There is no opt-out: `nonServiceApp()` would skip the stage but also skips CNAME registration (`helmInstall.groovy:180`), so the service would have no hostname. Write the test |
| Try-it-out fails in browser, curl works | CORS. Check `demo.allowed-origins`, and that preflight is exempt in `ClientIdResolutionFilter` |
| Try-it-out fails at DNS | Non-prod hosts are internal. Prod is a `platform.hmcts.net` host behind Front Door — see Deployment in README |
| Intermittent 401 after a deploy | `DEMO_SIGNING_KEY_JWK` not set, so each pod generates its own key. Terraform creates it (`tls_private_key` in `infrastructure/main.tf`); if it is missing, the apply has not run for that environment |
| Pods stuck `FailedMount` on `try-slc-DEMO-SIGNING-KEY-JWK` | The CSI driver fails the **whole** mount if any listed secret is absent, so helm waits out the 40-minute `Install Charts to AKS` timeout. Terraform owns this secret — it holds a PKCS#8 **PEM**, not a JWK, despite the name; `DemoSigningKey` accepts either |
| `/info` answers `{}` locally | Expected. The CNP pipeline writes `build-info.properties` into `src/main/resources/META-INF` before `assemble`; a local build has no such file. Only pipeline-built artefacts carry one |
| `processResources` fails: *Entry META-INF/build-info.properties is a duplicate* | Something added a `springBoot.buildInfo` block. The pipeline already generates that file, so Gradle generating a second one collides. **Do not fix this with `duplicatesStrategy`** — the generated file then wins and the pipeline's `build.commit` and `build.number` are lost. Remove the block; see the note in `build.gradle` |
| `bootJar` fails: *Could not find org.openapitools:openapi-generator-core* | `api-cp-*` publishes the OpenAPI generator, `swagger-parser` and `swagger-annotations` in its **runtimeElements** variant, though all three are build-time only. `apiElements` declares none of them, so `compileJava` passes and `bootJar` — the first task to resolve `runtimeClasspath` — is where it breaks. They are excluded in `build.gradle`; **do not re-add**. Green locally, red on Jenkins, because two of the coordinates are not resolvable on the CNP agent |
| Configuration fails: *Could not resolve io.github.ben-manes:gradle-versions-plugin* | That plugin was removed. Its artefact is not on Maven Central - only the Gradle Plugin Portal, which serves downloads from a separate host (`plugins-artifacts.gradle.org`), so it is fragile on a CNP agent. Nothing ran `dependencyUpdates`; Renovate covers it. **Do not re-add it** - see the note in `build.gradle` |
| Jenkins logs `repository "<product>/try-slc" is not found` during Checkout | Benign. `Acr.hasRepoTag` runs `az acr repository show-tags` only to decide whether an image rebuild can be skipped, and swallows the failure (`Acr.groovy:626-631`). Jenkins prints the step failure before Groovy catches it, so it looks fatal. Expected until the first build pushes an image, and again after any product rename |
| Jenkins logs `The resource record '<product>-try-slc-staging' does not exist` during the AAT deploy | Benign, and the same shape as the row above. `AzPrivateDns.checkForCname` asks whether a CNAME already exists and swallows the failure (`AzPrivateDns.groovy:46-50`, `catch (e) { return false }`). `false` is the answer that makes `helmInstall.groovy:183` **register** the A record. Expected on a first deployment, and again after any product or host rename |
| Any `az ... show` failure in a Jenkins log | Check before chasing it. The CNP library probes Azure with `show` commands and treats failure as "does not exist yet", so a first deployment prints several that look fatal and are not — Jenkins logs the step failure before Groovy catches it. The real failure is the **last** one, and normally says `FAILURE`, `Timeout`, `Aborted` or `Finished:` |
| `pmd-analysis` red on a PR but `./gradlew build` green | PMD is not a Gradle task here — it runs only in the GitHub action, over `src/main/java` with `.github/pmd-ruleset.xml`. Checkstyle is what `build` runs. Reproduce with `pmd check --no-progress --no-cache -d src/main/java -f text -R .github/pmd-ruleset.xml`. The one that bites first is `CommentDefaultAccessModifier` on a package-private method: there is no `/* default */` convention anywhere in this repo or the live service, so make the method `private` and test through the public entry point instead |
| Startup fails naming a stub file | That stub drifted from the contract, or `build.gradle` pinned a new contract version |
| `bootRun` fails: *auth.key-source=ENTRA but AUTH_TENANT_ID is still the self-issued sandbox value* | The check working. `ENTRA` is the default; add `AUTH_KEY_SOURCE=SELF_ISSUED` to `.env`, or set all three of `AUTH_TENANT_ID`/`AUTH_AUDIENCE`/`AUTH_ISSUER` |
| In `ENTRA`, every token answers 401 with `INVALID_CLAIMS` | Usually `aud`. A v2.0 token carries the resource's bare application id GUID, not `api://…`; startup logs a `SECURITY:` warning when `auth.audience` is not a GUID. Next most likely: the app registration is not on `requestedAccessTokenVersion: 2`, so Entra issued a v1 token with an `sts.windows.net` issuer |
| In `ENTRA`, tokens verify but answer 403 | No `app.read` app role assigned to the calling application. `AuthorizationPolicy.KNOWN_ROLES` is the authority on the name — and it is `app.read` only, matching the live service. `web-api-marketplace` (`config/default.json:63`) grants that role on every application it registers, so a marketplace-issued consumer is fine; a hand-made registration carrying only `slc.read` is not |
| In `ENTRA`, a token that decodes fine is still 401 `INVALID_CLAIMS` | Check `iss` before anything else. The `hmctsextsbox` tenant publishes **two** issuers: `https://login.microsoftonline.com/<tid>/v2.0` for `client_credentials`, and `https://<tid>.ciamlogin.com/<tid>/v2.0` for the CIAM user journeys `web-api-marketplace` signs people in through. `EntraTokenValidator` matches `iss` exactly, so they are not interchangeable — app-only callers must use `login.microsoftonline.com`. Do not add a second issuer to the validator; it is byte-identical to the live service's |
| `/oauth2/v2.0/token` or `/.well-known/jwks.json` answers 404 | `auth.key-source=ENTRA`. Those endpoints do not exist in that realm — check `GET /scenarios` → `authentication.mode` |
| Functional tests skip most of the suite | Expected in `ENTRA`: the demo-credential tests `assumeTrue(isSelfIssued())`. Skips keep the stage green, which matters because Jenkins' `junit` step has no `allowEmptyResults` here. Preview and AAT staging are `SELF_ISSUED` precisely so CI does not run in that mode — if a PR's functional stage is skipping, someone changed `values.preview.template.yaml`. **Known gap for sandbox and AAT:** nothing exercises the 200 path there — only the 404s and the forged-token 401. Closing it needs a functional test client registered in the issuing tenant with `app.read`, its id and secret mounted from the `amp-try-slc-{env}` vault |
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
- **There are two charts, and only one of them is in this repo.** Jenkins deploys preview and the
  ephemeral `amp-try-slc-staging` slot from `charts/amp-try-slc/`. Flux deploys **sandbox, AAT and
  demo** from `stable/amp-try-slc` in **`hmcts-charts`** — a separately published copy — overlaid
  with the `HelmRelease` patches in `cnp-flux-config/apps/amp/amp-try-slc/{sbox,aat,demo}.yaml`.
  Editing `charts/amp-try-slc/values.yaml` therefore changes **nothing** in those three until the
  chart is republished, and the published copy is currently 0.0.1 while this repo is at 0.0.3 — it
  still carries `ADMIN_DISABLED`, removed here in PR #36. **Any environment variable the Flux-managed
  environments must have goes in the `cnp-flux-config` patch**, which applies to whichever chart
  version is live. This is the trap behind "I changed values.yaml and the pod still has the old
  config".
- **There are two Terraform directories and they must not be merged.** `infrastructure/` is the
  CNP one, run by Jenkins, holding the key vault, Postgres and the demo signing key.
  `apim/` registers the public contract with the SPS APIM instance and is run by GitHub Actions
  (`.github/workflows/terraform-apim.yaml`) against a different subscription and a different state
  backend. Putting APIM resources in `infrastructure/` makes every Jenkins apply try to create them
  with variables it has never heard of. See `apim/README.md`.

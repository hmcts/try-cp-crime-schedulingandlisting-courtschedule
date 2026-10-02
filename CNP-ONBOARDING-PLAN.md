# CNP Onboarding Plan — apim/tryitnow-slc

Service: `try-cp-crime-schedulingandlisting-courtschedule`
Product: `apim` · Component: `tryitnow-slc`

Status: ✅ Done · ⚠️ Decide first · ○ To do

> Modelled on `service-api-marketplace`'s onboarding. The `apim` team wiring it established —
> team-config, namespace kustomizations, vault, managed identity — is **already in place**, so this
> is a second component rather than a first onboarding.
>
> This service now ships exactly the way `apim-marketplace` and `apim-marketplace-web` do: Jenkins
> builds and pushes to ACR, Flux rewrites the image tag and reconciles. Everything that belongs in
> another team's repository is prepared under [`platform-prs/`](platform-prs/) and has **not** been
> raised.

---

## Names reference

| What | Name |
|---|---|
| GitHub repo | `try-cp-crime-schedulingandlisting-courtschedule` |
| GitHub topic | `jenkins-cft-j-z` |
| Jenkins product / component | `apim` / `tryitnow-slc` |
| Jenkins job path | `HMCTS_j_to_z/try-cp-crime-schedulingandlisting-courtschedule` |
| Kubernetes namespace | `apim` |
| Helm release / chart | `apim-tryitnow-slc` |
| Docker image | `hmctsprod.azurecr.io/apim/tryitnow-slc:{tag}` |
| Flux config path | `apps/apim/apim-tryitnow-slc/` |
| Key vault | `apim-{env}` (`apim-sbox` in sandbox) |
| Vault secret | `tryitnow-slc-DEMO-SIGNING-KEY-JWK` |
| Managed identity | `apim-{env}-mi`, chart `aadIdentityName: apim` |
| Internal ingress | `apim-tryitnow-slc-{env}.service.core-compute-{env}.internal` |
| AAT staging URL | `apim-tryitnow-slc-staging.aat.platform.hmcts.net` |
| Preview URL | `apim-tryitnow-slc-pr-{N}.preview.platform.hmcts.net` |

---

## Phase 0 — Decide exposure ⚠️

| # | Step | Status | Notes |
|---|---|---|---|
| 0.1 | Internal-only, or publicly reachable? | ⚠️ **Decide first** | The AAT host does not resolve publicly; the catalogue does. See "Exposure" in the README. Shapes 4.x and whether APIM routing work is needed. |

## Phase 1 — Repo

| # | Step | Status |
|---|---|---|
| 1.1 | Repo scaffolded from `service-api-marketplace` | ✅ Done |
| 1.2 | `Jenkinsfile_CNP`, `Jenkinsfile_nightly` | ✅ Done |
| 1.3 | Helm chart `charts/apim-tryitnow-slc/` | ✅ Done |
| 1.4 | `catalog-info.yaml` | ✅ Done |
| 1.5 | Application code, stubs, tests | ✅ Done |
| 1.6 | Create `hmcts/try-it-now-...` on GitHub and push | ○ To do |
| 1.7 | Add GitHub topic `jenkins-cft-j-z` | ○ To do |
| 1.8 | Branch protection on `master` | ○ To do |

## Phase 2 — Signing key

| # | Step | Status | Notes |
|---|---|---|---|
| 2.1 | Generate the demo RSA keypair as a JWK document | ○ To do | Any RSA 2048 JWK export; `kid` is free-form |
| 2.2 | `az keyvault secret set --vault-name apim-aat --name tryitnow-slc-DEMO-SIGNING-KEY-JWK` | ○ To do | |
| 2.3 | Same for `apim-sbox` if sandbox is wanted | ○ To do | |

> Skipping this does not block a deploy — the service starts with an ephemeral key and logs a
> warning — but every Flux redeploy would then invalidate every issued token.

## Phase 3 — cnp-jenkins-config

| # | Step | Status | Notes |
|---|---|---|---|
| 3.1 | Add to `deployment-controls.yml` | ○ To raise | Snippet in `platform-prs/cnp-jenkins-config/` |
| 3.3 | `infrastructure/` with `terraform-module-postgresql-flexible` | ✅ Done | Its **own** server, `apim-tryitnow-flexible-{env}`, database `tryitnow`. Not a second database on `apim-flexible` — two repos running terraform against one server fight over state. The vault is consumed as a data source, as `web-api-marketplace` does for Redis |
| 3.4 | Entra app registration for the admin realm, with `app.recordings.write` and `app.recordings.publish` app roles | ○ To do | Until this exists the chart sets `ADMIN_DISABLED: "true"` and `/admin/**` answers 503. The service refuses to start with a half-configured admin realm rather than running it unguarded |
| 3.2 | `terraform-infra-approvals/<repo>.json` | ○ To raise | Written, in `platform-prs/`. Lists the postgres module and `azurerm_key_vault_secret` only — **not** `cnp-module-key-vault`, which this repo does not create |
| 3.5 | `Jenkinsfile_parameterized` for the sandbox job | ✅ Done | `withParameterizedPipeline('java', 'apim', 'tryitnow-slc', ...)`, matching both existing components |
| 3.6 | `.terraform-version` at repo root | ✅ Done | `1.16.1`, matching `service-api-marketplace`. Sandbox agents need it for tfenv |

## Phase 4 — cnp-flux-config

| # | Step | Status | Notes |
|---|---|---|---|
| 4.1 | `apps/apim/` namespace kustomizations | ✅ Done | Established by `apim-marketplace` |
| 4.2 | `apps/apim/apim-tryitnow-slc/apim-tryitnow-slc.yaml` HelmRelease | ○ To raise | Written, in `platform-prs/` |
| 4.3 | Image policy and repository | ○ To raise | Written. Prefer regenerating: `./add-image-policies.sh apim apim tryitnow-slc hmctsprod` — the trailing registry is required, the script still defaults to `hmctspublic` |
| 4.4 | Add to `apps/apim/base/kustomization.yaml` | ○ To raise | Under `resources`. Note the env bases take `patches` instead — adding to the wrong key silently does nothing |
| 4.5 | Env patches (`aat.yaml`, `sbox.yaml`, `demo.yaml`) | ○ To raise | Written. **No preview patch** — `apps/apim/preview/base` does not include the HelmReleases at all; PR environments are Jenkins-deployed |
| 4.7 | Prod overlay | ⚠️ Out of scope | `apps/apim/prod/` does not exist for this product, and neither existing component is in `environment-approvals.yml` |
| 4.6 | Workload identity | ✅ Done | `apim` service account already wired; chart uses `aadIdentityName: apim` |

## Phase 5 — First build and verify

| # | Step | Status | Command |
|---|---|---|---|
| 5.1 | Jenkins seed job / org scan picks up the repo | ○ To do | |
| 5.2 | First PR build green | ○ To do | |
| 5.3 | Preview health check | ○ To do | `https://apim-tryitnow-slc-pr-{N}.preview.platform.hmcts.net/health` |
| 5.4 | AAT staging health check | ○ To do | `https://apim-tryitnow-slc-staging.aat.platform.hmcts.net/health` |
| 5.5 | Smoke: `GET /scenarios`, then token → API call | ○ To do | |
| 5.6 | Jenkins check added to branch protection | ○ To do | |

## Phase 6 — Catalogue wiring

| # | Step | Status | Notes |
|---|---|---|---|
| 6.1 | Decide where the sandbox URL is published | ○ To do | `amp-catalog/docs/apis.json` entries carry no mock/server field today |
| 6.2 | Add `securitySchemes` + a second `servers` entry to the api-cp spec | ○ To do | Without it Swagger UI has no **Authorize** button — see README |
| 6.3 | Fix the spec's `examples` (wrong shape) | ○ To do | Separate PR against the api repo; see README |

---

## Verified locally

The scaffold was built and run before hand-off:

- `./gradlew build` green; 31 tests including the stub-vs-contract gate
- Service boots, `/health` answers 200 **unauthenticated** — confirming the actuator exemption works
  with `management.endpoints.web.base-path: /`, which would otherwise 401 the liveness probe and
  CrashLoopBackOff the pod
- Full journey exercised: token → 200 for each success scenario, 404/400/500 for the error
  scenarios, genuine 403 for the unentitled client, genuine 401 for a wrong secret
- CORS preflight from `https://hmcts.github.io` answers 200; a disallowed origin is refused; the
  real request still requires a token

# apim — APIM registration

Terraform that registers this sandbox's **public** contract with the HMCTS Shared Platform
Services APIM, so a prospective consumer can reach it without a VPN.

Modelled on `api-cp-crime-schedulingandlisting-courtschedule/infrastructure`. Read that first if
this is unfamiliar — the differences are the interesting part, and they are listed below.

## Why not `infrastructure/`?

Because `infrastructure/` is already taken. The CNP Jenkins pipeline runs its own Terraform there
for the key vault, the Postgres server and the demo signing key, with its own state backend and
its own `aat`/`demo`/`prod`/`sandbox` tfvars. Adding APIM resources to that directory would make
every Jenkins apply try to create them too, with variables it has never heard of.

Two directories, two states, two pipelines. `apim/` is driven only by
`.github/workflows/terraform-apim.yaml`; Jenkins never looks at it.

| | `infrastructure/` | `apim/` |
|---|---|---|
| Driven by | Jenkins (`Jenkinsfile_CNP`) | GitHub Actions (`terraform-apim.yaml`) |
| Creates | Key vault, Postgres, signing key | APIM product and API |
| Subscription | CNP | DTS-SPS-\* |

## What it creates

| Resource | Detail |
|---|---|
| APIM Product | `try-it-now-cp-crime-schedulingandlisting` |
| APIM API | `public` — at path `amp/try/slc`, built from **two** specs (below) |
| API policy | Rate limit, quota, concurrency, plus `validate-jwt` **where the realm is ENTRA** — see `policies/api-policy.xml` |

## What APIM publishes, and why it is not one whole spec

Two operations, assembled from two contracts:

| Operation | From |
|---|---|
| `GET /scenarios` | `contracts/try-it-now-public.openapi.yml` |
| `GET /case/{case_urn}/courtschedule` | `contracts/slc-courtschedule.openapi.yml` |

`expose_paths` in the tfvars limits the sandbox contract to `/scenarios`. **`POST /oauth2/v2.0/token`
and `GET /.well-known/jwks.json` are deliberately not published**: they are the self-issued surface
and do not exist at all where `auth.key-source=ENTRA`, which is what sandbox runs, so publishing
them would document a 404. A consumer reaching this through APIM gets its token from Entra.

`merge_spec_path` brings in the API actually being demonstrated, so a consumer sees one API with
the discovery endpoint and the real shape together rather than two half-APIs. The merge is paths
plus `components.schemas`; the two specs' schema names were checked and do not overlap.

`contracts/slc-courtschedule.openapi.yml` is **generated, not hand-maintained** — `./gradlew
syncSlcContract` copies it out of the pinned `api-cp` jar, and `verifySlcContract` runs as part of
`check`, so a drifted copy fails the build instead of reaching APIM. It is the same spec the
generated `CourtScheduleApi` comes from, so what APIM publishes and what the service implements
cannot disagree. Bump the dependency, run the sync, commit the result.

The OpenAPI spec is the source of truth: display name, operations and schemas all come from it.

`try-it-now-public.openapi.yml` is the only contract this service has. A write surface is expected
to come back; when it does it must **not** be registered here. See `contracts/README.md`.

## Three differences from the api-cp version

**1. `service_url` is given whole, not composed.** The api-cp Terraform builds
`"https://${service_host}.org.uk${service_path}"` because every backend it registers is a Common
Platform app gateway. This sandbox runs in CNP, where hosts end `.platform.hmcts.net`, so that
expression would append `.org.uk` to a host that does not have it.

**2. `validate-jwt` is per-environment, not absent.** The policy is rendered by `templatefile()`
and the `validate-jwt` block appears only where `entra_tenant_id` is set. That is sandbox and
nothing else, because `auth.key-source` is `ENTRA` there and `SELF_ISSUED` everywhere else — an
Entra `validate-jwt` in front of a self-issued deployment would reject every token it is handed.
Leave the variable unset in a `SELF_ISSUED` environment's tfvars and the gateway falls back to
throughput protection alone.

`GET /scenarios` is exempt even in sandbox. It is the discovery endpoint: a consumer reads
`authentication.mode` from it to learn which realm they are in, so it has to answer before they
hold a token. The exemption is an allow-list of one on `UrlTemplate`, so an operation added later
is protected by default rather than open by accident.

`required_roles` deliberately mirrors api-cp's `["slc.read", "app.read"]`, which is wider than this
service's own `KNOWN_ROLES` of `app.read` alone. That gap is the behaviour being rehearsed: against
the real API a caller holding only `slc.read` passes APIM and is refused 403 by the service.

**3. No prod.** Demo is the last environment for this service. There is no `prod.tfvars` and no
prod job in the workflow.

## CI/CD

| Trigger | Action |
|---|---|
| Pull request to `master` | `terraform plan`, posted as a PR comment |
| Push to `master` | `terraform apply` |
| Manual dispatch | Choose `plan` or `apply` |

Environments are discovered from `*.tfvars` in this directory, so adding `aat.tfvars` adds an aat
job with no workflow change. `aat` is gated behind a GitHub environment approval.

Authentication is OpenID Connect — no secrets are stored. Terraform state is keyed by repository
name (`<repo>/<env>/terraform.tfstate`), so sharing the `spsapimapi<env>state` account with the
api-cp repo is safe.

### Required GitHub Actions variables

Set on the repository under Settings → Secrets and variables → Variables:

Note that the two subscription variables hold different things — a name and a GUID. The values
below are the ones set on `api-cp-crime-schedulingandlisting-courtschedule`, which targets the same
APIM instance.

| Variable | Value |
|---|---|
| `AZURE_CLIENT_ID_SBOX` | Client ID of **this repo's** OIDC app registration (not the api-cp one) |
| `AZURE_SUBSCRIPTION_SBOX` | `DTS-SPS-SBOX` — the subscription *name* |
| `AZURE_SUBSCRIPTION_ID_SBOX` | `bd2864ed-4f3e-45ed-9c6a-8d179674bab1` — the subscription *id* |
| `TFSTATE_STORAGE_ACCOUNT_NONPROD` | Storage account for non-prod Terraform state |

The app registration must be this repository's own, because the federated credential is pinned to a
subject that names the repo: `repo:hmcts/try-cp-crime-schedulingandlisting-courtschedule:ref:refs/heads/master`.
It needs Contributor on the APIM instance.

## Acceptance test

APIM's runtime behaviour cannot be checked without an instance, so the first apply is the proof.
The API sets `subscription_required = false` inside a product that sets it `true` — supported, and
the reason discovery works without a key. Confirm it:

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  https://sps-api-mgmt-sbox.azure-api.net/amp/try/slc/scenarios
```

`200` means the open-API setting is honoured. `401 Access denied due to missing subscription key`
means it is not, and the flag has to move to the product. Check the gateway hostname against the
first plan output — `azure-api.net` is the default, but SPS may use a custom domain.

## Running locally

```bash
az login
az account set --subscription bd2864ed-4f3e-45ed-9c6a-8d179674bab1

cd apim
terraform init
terraform plan -var-file=sbox.tfvars
```

## Adding an environment

1. Copy `sbox.tfvars` to `<env>.tfvars`.
2. Update `api_mgmt_rg`, `api_mgmt_name` and the API's `service_url` for that environment.
3. Set `AZURE_CLIENT_ID_<ENV>`, `AZURE_SUBSCRIPTION_<ENV>` and `AZURE_SUBSCRIPTION_ID_<ENV>` on the repo.
4. Raise a PR — the workflow picks it up automatically.

## Open: the domain API

A consumer is meant to see one catalogue entry made of two specs — the sandbox plumbing here, and
the API being demonstrated, which lives in
`api-cp-crime-schedulingandlisting-courtschedule/src/main/resources/openapi/openapi-spec.yml`.

That file is not in this repository, and `openapi_spec_path` is a path in the Terraform checkout,
so it cannot be referenced from here. The natural home for it is the repo that owns it: one more
entry in **that** repo's `apis` map, pointing at this sandbox instead of the Common Platform
gateway.

```hcl
# in api-cp-.../infrastructure/sbox.tfvars
try_it_now = {
  openapi_spec_path = "../src/main/resources/openapi/openapi-spec.yml"
  display_name      = "Crime Scheduling and Listing Schedule API (Try It Now)"
  path              = "amp/try/slc/api"
  service_host      = "..."   # needs the service_url escape hatch this repo added
  required_roles    = ["app.read"]
}
```

It needs the same `service_url` change made here. The JWT question is now answered the same way it
is here: set `entra_tenant_id` only where the realm is `ENTRA`. A demo token will not pass any
`validate-jwt`, so a `SELF_ISSUED` environment must leave it unset.

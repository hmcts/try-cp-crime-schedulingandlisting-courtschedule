# Platform PRs

The files here belong in **other teams' repositories**, not this one. They are prepared and
reviewable so the change is a copy rather than a transcription, but raising them is a
deliberate act against shared platform repos — nothing here has been pushed.

Everything is modelled on what `apim-marketplace` and `apim-marketplace-web` already have, so
this service ships exactly the way they do.

## 1. `hmcts/cnp-jenkins-config`

| File | Change |
|---|---|
| `deployment-controls.yml` | Add the entry in `deployment-controls.snippet.yml`, keeping the file's alphabetical order by repo URL |
| `terraform-infra-approvals/try-it-now-cp-crime-schedulingandlisting-courtschedule.json` | New file. Whitelists only what `infrastructure/` actually creates — the postgres module and vault secrets. It does **not** include `cnp-module-key-vault`: the vault is created by `service-api-marketplace` and consumed here as a data source |

Without the approvals file the pipeline refuses the terraform plan, which is the state
`web-api-marketplace` is in today.

## 2. `hmcts/cnp-flux-config`

New folder `apps/apim/apim-tryitnow-slc/` containing:

| File | Purpose |
|---|---|
| `apim-tryitnow-slc.yaml` | The HelmRelease. Carries the `$imagepolicy` marker Flux rewrites on each build |
| `image-repo.yaml` | Points at `hmctsprod.azurecr.io/apim/tryitnow-slc` |
| `image-policy.yaml` | Selects the newest `prod-{sha}-{timestamp}` tag. `prod-automated: disabled`, matching both existing components |
| `aat.yaml`, `demo.yaml`, `sbox.yaml` | Per-environment ingress host and vault mounts |

Then four one-line edits to existing kustomizations. **Apply the marked line to whatever the file
says at the time — do not copy these files over the top.** They were verified identical to the live
versions apart from the addition on 2 October 2026, but they are a snapshot of someone else's repo
and copying one wholesale would silently revert any change made since:

| File | Added |
|---|---|
| `apps/apim/base/kustomization.yaml` | `- ../apim-tryitnow-slc/apim-tryitnow-slc.yaml` under `resources` |
| `apps/apim/aat/base/kustomization.yaml` | `- path: ../../apim-tryitnow-slc/aat.yaml` under `patches` |
| `apps/apim/sbox/base/kustomization.yaml` | `- path: ../../apim-tryitnow-slc/sbox.yaml` |
| `apps/apim/demo/base/kustomization.yaml` | `- path: ../../apim-tryitnow-slc/demo.yaml` |

Note the two kinds of edit: the shared `base` lists **HelmRelease paths** under `resources`,
while each environment's `base` lists **patch paths** under `patches`. Adding to the wrong one
silently does nothing.

**No preview patch.** `apps/apim/preview/base` does not include the HelmReleases at all — PR
environments are deployed by Jenkins, not reconciled by Flux. `apim-marketplace` has a
`preview.yaml` that nothing references; do not copy that.

**No prod overlay.** `apps/apim/prod/` does not exist for this product. Production needs its own
change, plus an `environment-approvals.yml` entry, and is out of scope here.

Image policies are normally generated rather than hand-written:

```bash
./add-image-policies.sh apim apim tryitnow-slc hmctsprod
```

The trailing `hmctsprod` is required — the script still defaults to `hmctspublic`, which is the
wrong registry and produces a policy that never matches a tag.

## 3. Not covered here

Three steps are manual and cannot be expressed as a file:

1. **Create the GitHub repo**, add topic `jenkins-cft-j-z`, enable branch protection on `master`.
2. **Seed the demo signing key** into each vault, once:
   `az keyvault secret set --vault-name apim-aat --name tryitnow-slc-DEMO-SIGNING-KEY-JWK --file demo-signing-key.jwk.json`
3. **Register the admin realm app** in the corporate Entra tenant with app roles
   `app.recordings.write` and `app.recordings.publish`. Until it exists the chart sets
   `ADMIN_DISABLED: "true"` and `/admin/**` answers 503.

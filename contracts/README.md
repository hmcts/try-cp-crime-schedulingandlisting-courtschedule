# Try It Now contracts

Two OpenAPI specs. Both are **shared by every try-it-now service** — only the API being
demonstrated differs, and that comes from the service's own `api-cp-*` contract.

| Spec | Audience | In APIM? | Guarded by |
|---|---|---|---|
| `try-it-now-public.openapi.yml` | Prospective API consumers | **Yes**, alongside the `api-cp-*` spec | Nothing — unauthenticated by design |
| `try-it-now-admin.openapi.yml` | Whoever uploads captured examples | **No** | The ingress, not the application — see below |

A consumer therefore sees one catalogue entry made of **two** specs: the sandbox plumbing
(`/scenarios`, the demo token endpoint, JWKS) and the real API shape. The admin surface is not part
of it.

## Why `/scenarios` is in the public contract and `/admin/**` is not

The demo credentials are printed in `/scenarios`. Everything reachable with them has to be
read-only and safe to publish. The admin API writes data that ends up on a public endpoint, so it
is a separate contract, a separate ingress and a separate control. Keeping them in one spec would
mean one APIM product and one set of policies covering both.

## The package constraint

Every `api-cp-*` artefact generates into `uk.gov.hmcts.cp.openapi.{api,model}`, and the SLC
contract jar is already on this service's classpath. These specs therefore generate into
`uk.gov.hmcts.cp.tryitnow.openapi.*` and `uk.gov.hmcts.cp.tryitnow.admin.openapi.*` — distinct from
the api-cp packages **and from each other**.

The second part is not cosmetic: the generator emits an `ApiUtil` helper into whichever API package
it is given, so pointing both specs at one package produces a duplicate class. It fails
confusingly, too — javac aborts annotation processing on a duplicate class, so Lombok's generated
members appear to vanish and you get dozens of unrelated `cannot find symbol` errors elsewhere.

## Linting

`contracts/.spectral.yml` deliberately does **not** inherit the `api-cp-*` repositories'
configuration, which switches `oas3-valid-media-example` and `oas3-valid-schema-example` off. Those
are the rules that would have caught the SLC contract's examples not matching their own schema.
Both specs here lint clean with them on:

```bash
npx @stoplight/spectral-cli lint --ruleset contracts/.spectral.yml contracts/*.openapi.yml
```

## Where these should eventually live

In their own repository — `api-try-it-now` or similar — published as a jar, so every try-it-now
service consumes the same version rather than copying the files. They are here for now because
that repository does not exist yet and creating one is a decision, not a detail.

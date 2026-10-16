# Try It Now contracts

One OpenAPI spec, **shared by every try-it-now service** — only the API being demonstrated
differs, and that comes from the service's own `api-cp-*` contract.

| Spec | Audience | In APIM? | Guarded by |
|---|---|---|---|
| `try-it-now-public.openapi.yml` | Prospective API consumers | **Yes**, alongside the `api-cp-*` spec | Nothing — unauthenticated by design |

A consumer therefore sees one catalogue entry made of **two** specs: the sandbox plumbing
(`/scenarios`, the demo token endpoint, JWKS) and the real API shape.

## Everything in this spec is safe to publish

The demo credentials are published in the service README, so everything reachable with them has to
be read-only. That holds trivially today: the service has no write surface at all.

They are deliberately **not** in the `/scenarios` response. That response is read by consumers
evaluating the live API, most of whom hold an Entra token from their own registration, and a client
id and secret in it would invite them to use the wrong one.

A write surface is expected to come back, and when it does it belongs in a spec of its own, behind
its own ingress and its own control — never in this one. Putting both in one spec would mean one
APIM product and one set of policies covering a published read API and a privileged write API
together.

## The package constraint

Every `api-cp-*` artefact generates into `uk.gov.hmcts.cp.openapi.{api,model}`, and the SLC
contract jar is already on this service's classpath. These specs therefore generate into
`uk.gov.hmcts.cp.tryitnow.openapi.*`, distinct from the api-cp packages.

Any second spec added here needs a package of its own as well, and that is not cosmetic: the
generator emits an `ApiUtil` helper into whichever API package it is given, so two specs sharing one
package produce a duplicate class. It fails confusingly, too — javac aborts annotation processing on
a duplicate, so Lombok's generated members appear to vanish and you get dozens of unrelated
`cannot find symbol` errors elsewhere.

## Linting

`contracts/.spectral.yml` deliberately does **not** inherit the `api-cp-*` repositories'
configuration, which switches `oas3-valid-media-example` and `oas3-valid-schema-example` off. Those
are the rules that would have caught the SLC contract's examples not matching their own schema.
The spec here lints clean with them on:

```bash
npx @stoplight/spectral-cli lint --ruleset contracts/.spectral.yml contracts/*.openapi.yml
```

## Where these should eventually live

In their own repository — `api-try-it-now` or similar — published as a jar, so every try-it-now
service consumes the same version rather than copying the files. They are here for now because
that repository does not exist yet and creating one is a decision, not a detail.

# Building, deploying and testing a Spring Boot service on CNP

A practical guide for an engineer onboarding a **new** service to the HMCTS Cloud Native Platform
(CNP), written from the files that actually run the thing rather than from the platform's summary of
itself. This repo — product `amp`, component `try-slc`, chart `charts/amp-try-slc` — is the worked
example throughout.

The guide ends where a new service first exists as a running, reachable thing: **deployed to
sandbox and answering `https://amp-try-slc.sandbox.platform.hmcts.net/health` over the VPN**. The
preview environment is in scope, because it is the PR path and you will meet it first. AAT, demo and
APIM registration get one-line pointers only.

For *this* service's remaining onboarding steps and their status, see
[`../CNP-ONBOARDING-PLAN.md`](../CNP-ONBOARDING-PLAN.md) — that file is the status tracker, this one
is the method. Repo-level conventions and the debugging table are in
[`../CLAUDE.md`](../CLAUDE.md).

## How to read the citations

Every path, hostname and command below comes from a file that was read or a page that was fetched.
Where a specific line matters it is cited as `path:line`.

Citations to the Jenkins shared library (`vars/*.groovy`, `src/uk/gov/hmcts/contino/*.groovy`) are
against **`hmcts/cnp-jenkins-library` at tag 2.12.2**. `Jenkinsfile_CNP:3` pins
`@Library("Infrastructure@2.7.2")`, and `Jenkinsfile_nightly` pins nothing at all. The citations
this guide leans on were re-checked at 2.7.2 and the line numbers are identical; the rest were not.
The closing section says which. To check any other one, `git show 2.7.2:<path>` in that repo. Citations to `cnp-flux-config` and
`cnp-jenkins-config` are against local clones of `master` taken on 7 October 2026
(`cnp-flux-config` at `596ee0520c9`); both repos change daily.

---

## 1. What CNP is, and the official docs

CNP is HMCTS's shared Azure platform: AKS clusters per environment, a Jenkins estate that builds and
tests, Flux that reconciles what actually runs, Azure Container Registry for images, Key Vault for
secrets, and a set of central configuration repos that a new component must be registered in.

The division of labour is the thing to internalise, because almost every confusing failure comes
from getting it wrong:

| Concern | Owned by | Lives in |
|---|---|---|
| Build the jar, build and push the image | Jenkins | `Jenkinsfile_CNP` in this repo |
| Create Azure resources (vault, database, secrets) | Jenkins, running Terraform | `infrastructure/` in this repo |
| Deploy a **throwaway** release for testing | Jenkins | `charts/amp-try-slc/` + the pipeline |
| Deploy the **durable** release you can point someone at | Flux | `apps/amp/` in `cnp-flux-config` |
| Decide whether Jenkins is allowed to deploy at all | PlatOps | `deployment-controls.yml` in `cnp-jenkins-config` |

The official checklist is **<https://hmcts.github.io/cloud-native-platform/new-component/>**. Fetched
on 13 October 2026, it reads (the page itself carries a staleness banner: last reviewed 29 July 2026,
next review due 29 January 2027):

**Prerequisites** — GitHub repo access and team ownership for the product or component; Jenkins
access and the repository topic needed for the organisation scan; Azure access, **VPN access**, and
developer tools; the repo for any shared infrastructure the component depends on; a target
environment for the first deployment, "usually preview or AAT".

**Steps**

1. Create a new GitHub repository, then do the runtime-specific setup. For Java it points at
   [`hmcts/spring-boot-template`](https://github.com/hmcts/spring-boot-template).
2. Set up Jenkins, including deployment controls: add the repository to Jenkins, add the GitHub
   topic so the organisation scan picks it up, and add the `deployment-controls.yml` entry that
   turns on the build and deploy stages.
3. Configure GitOps with Flux, and deploy the app.
4. Set up infrastructure as code, if you need databases or storage.
5. Set up secrets management, if needed.
6. Configure TLS certificates, if publicly exposed.
7. Configure public DNS.
8. Configure the load balancer, for backend services.
9. Configure Front Door, for frontend services.
10. Deploy to a non-production environment and validate; then move on to Path to Live.

The page names no repositories for the PRs in steps 2 and 3 beyond `deployment-controls.yml`, and
gives no commands. Sections 7 and 8 below fill that gap from the config repos themselves.

Steps 6, 8 and 9 do not apply to this service and are not covered here.

---

## 2. Prerequisites

### The GitHub repo and the Jenkins topic

The repo must live in the `hmcts` org, and it is a **GitHub topic** that puts it in front of
Jenkins. `cnp-jenkins-config/jobdsl/organisations-beta.groovy:40-42` defines three organisation
folders and the topic each one scans for:

| GitHub topic | Jenkins folder |
|---|---|
| `jenkins-cft-a-c` | `HMCTS_a_to_c` |
| `jenkins-cft-d-i` | `HMCTS_d_to_i` |
| `jenkins-cft-j-z` | `HMCTS_j_to_z` |

Pick by the first letter of the **repository** name. This repo is
`try-cp-crime-schedulingandlisting-courtschedule`, so the topic is `jenkins-cft-j-z` and the job
lands at `HMCTS_j_to_z/try-cp-crime-schedulingandlisting-courtschedule` — which is what
`catalog-info.yaml:7` records as `jenkins.io/job-full-name`.

Without the topic the organisation scan never sees the repo and no job is ever created. That is the
single most common "why is nothing happening" at the start.

### Branch protection

Protect `master` and require the Jenkins check once the first build has produced one. The chicken
and egg is real: the check does not exist as a selectable status until a build has reported it, so
protect the branch first and add the required check afterwards. (That ordering is how
[`../CNP-ONBOARDING-PLAN.md`](../CNP-ONBOARDING-PLAN.md) sequences it at 1.8 and 5.6; the GitHub
behaviour itself is **unverified** here.)

### Renovate is mandatory

Every HMCTS repo must carry a Renovate config extending the org config. This repo's `renovate.json`
is the whole of it:

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": [
    "local>hmcts/.github:renovate-config"
  ]
}
```

Do not add `enabledManagers` — narrowing the managers is how a repo ends up silently not getting
security updates for the ecosystem you forgot to list. Renovate is also the reason this repo does
*not* carry a dependency-report plugin: the note at `build.gradle:111-120` records that
`com.github.ben-manes.versions` was removed because its artefact is not on Maven Central and failed
to resolve on a CNP Jenkins agent, and because Renovate reports the same information continuously as
PRs.

### Azure and VPN

You need Azure access to the non-prod subscriptions and you need the VPN before you can resolve, let
alone curl, any sandbox or AAT hostname. See section 8.

---

## 3. The service itself

### Dockerfile

```dockerfile
 # renovate: datasource=github-releases depName=microsoft/ApplicationInsights-Java
ARG APP_INSIGHTS_AGENT_VERSION=3.7.9
FROM hmctsprod.azurecr.io/base/java:25-distroless

COPY lib/applicationinsights.json /opt/app/
COPY build/libs/amp-try-slc.jar /opt/app/

EXPOSE 8080
CMD [ "amp-try-slc.jar" ]
```

Four things matter here:

- The base image comes from `hmctsprod.azurecr.io/base/java`, not Docker Hub. It is distroless, so
  there is no shell in the container — `kubectl exec ... -- sh` will not work, and debugging is done
  through logs and probes.
- The `CMD` is the jar *name*, not `java -jar`. The base image presumably supplies the entrypoint
  that turns that into a JVM invocation; **unverified** — the contents of
  `hmctsprod.azurecr.io/base/java` were not read. `docker inspect` on the base image would confirm.
- `APP_INSIGHTS_AGENT_VERSION` is declared as an `ARG` **before** `FROM` and is never referenced
  afterwards, so within this Dockerfile its only effect is to carry the Renovate annotation above it
  and keep the pinned version visible. Whether anything downstream consumes it is **unverified**.
- The jar name is fixed by `build.gradle:242` (`bootJar { archiveFileName = "amp-try-slc.jar" }`) and
  must match the `COPY` and the `CMD`. Rename one without the other and the image fails at `COPY`
  (wrong jar name) or at start (wrong `CMD`).

`.dockerignore` denies everything and then re-admits only `Dockerfile`, `lib/` and
`build/libs/*.jar` — the pipeline appends its own exclusions to whatever `.dockerignore` it finds
(`vars/sectionBuildAndTest.groovy:122-131`), so keep yours allow-list shaped.

### Gradle build

The HMCTS plugin `uk.gov.hmcts.java` (`build.gradle:11`) brings Checkstyle and the OWASP dependency
checker. Four source sets exist, and three of them are *created by this repo*, not by the plugin
(`build.gradle:27-38`):

| Source set | Gradle task | Run by |
|---|---|---|
| `src/test` | `test` (via `check`) | Jenkins "Unit tests and Sonar scan" |
| `src/integrationTest` | `integration` (wired into `check` at `build.gradle:101`) | the same stage |
| `src/smokeTest` | `smoke` | Jenkins "Smoke Test - AKS `<env>`", against a deployed URL |
| `src/functionalTest` | `functional` | Jenkins "Functional Test - `<env>`", against a deployed URL |

The pipeline calls `smoke` and `functional` with `--rerun-tasks`
(`src/uk/gov/hmcts/contino/GradleBuilder.groovy:97,123`) so that re-running against a second
environment is not skipped as up to date.

Two repo-specific build facts worth carrying to a new service:

- **Do not add a `springBoot.buildInfo` block.** The pipeline writes
  `src/main/resources/META-INF/build-info.properties` itself before `assemble`, carrying the commit
  and build number; a Gradle-generated second copy makes `processResources` fail on a duplicate
  entry, and "fixing" it with `duplicatesStrategy` throws away the pipeline's richer version. The
  reasoning is recorded at `build.gradle:226-239`. The consequence is that `GET /info` returns `{}`
  from a locally built jar and is populated only from a pipeline-built one.
- **Watch for build-time tooling leaking onto the runtime classpath.** `build.gradle:153-171`
  excludes `openapi-generator-core`, `swagger-parser` and `swagger-annotations` from the `api-cp-*`
  contract jar, because that jar declares them in its `runtimeElements` variant. `compileJava`
  resolves `apiElements` and passes; `bootJar` is the first task to resolve `runtimeClasspath`, so
  the failure is green locally and red on Jenkins, where two of the coordinates do not resolve.

### Spring Boot Actuator and the health endpoints

This is the part that generic advice gets wrong, and it costs forty minutes a time.

`src/main/resources/application.yaml` sets:

```yaml
management:
  endpoint:
    health:
      show-details: "always"
      probes:
        enabled: true
  endpoints:
    web:
      base-path: /
      exposure:
        include: health, info, prometheus
```

`probes.enabled: true` is what creates `/health/liveness` and `/health/readiness` as separate
endpoints. `base-path: /` puts the actuator at the context root rather than under `/actuator`.

**`chart-java` probes the health *sub-paths*, not `/health`.** From `chart-java` 6.1.1's
`java/values.yaml` (fetched from GitHub raw — the chart is pulled from
`oci://hmctsprod.azurecr.io/helm` at deploy time and is not a file in this repo):

| Probe | Default path | Line |
|---|---|---|
| readiness | `/health/readiness` | `java/values.yaml:14` |
| liveness | `/health/liveness` | `java/values.yaml:18` |
| startup | `/health/liveness` | `java/values.yaml:23` |

The chart renders each probe only if its path value is set
(`chart-library/library/templates/v2/_container.tpl:77-105` — a local clone of `hmcts/chart-library`
whose version was not pinned down; `chart-java` 6.1.1 depends on `library` 2.3.1), and the startup
probe defaults to 20
failures at a 10-second period (`java/values.yaml:26-27`).

So a service that exempts only `/health` from authentication leaves `/health/liveness` answering
401, the pod never becomes ready, and Helm sits in `--wait` until the stage's 40-minute timeout
expires (section 10). `src/smokeTest/java/uk/gov/hmcts/cp/SmokeTest.java` guards exactly this with a
test named *"the liveness probe path chart-java actually uses is reachable"*.

Note there are **two** independent consumers of the health endpoint: the Kubernetes probes above,
and the pipeline's own post-install check, which polls `https://<fqdn>/health`
(`vars/helmInstall.groovy:189-191`). Both must be unauthenticated.

In this repo the exemption lives in `uk.gov.hmcts.cp.auth.AuthorizationPolicy`, which treats
`/health` and its groups as a rule rather than an enumeration — so a new health group is covered
automatically — and lists `/info` and `/prometheus` explicitly, because with `base-path: /` the
prefix rule cannot match them. See the debugging table in [`../CLAUDE.md`](../CLAUDE.md).

### Local development

Postgres first, then the app:

```bash
docker run -d --name tin-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=tryitnow \
  -p 55432:5432 postgres:16-alpine

POSTGRES_PORT=55432 ADMIN_DISABLED=true ./gradlew bootRun
curl localhost:8080/scenarios
curl localhost:8080/health
curl localhost:8080/health/liveness
```

**Publish the container on 55432, not 5432.** A native Postgres already listening on
`127.0.0.1:5432` silently shadows the Docker container — connections succeed, against the wrong
server, and the app fails with `role "postgres" does not exist`. The error names a role, so it reads
like a permissions problem rather than a wrong-server problem, which is what makes it expensive.
`application.yaml:26` reads `${POSTGRES_PORT:5432}`, so the override is a single environment
variable. (`../README.md:369-370` still shows `-p 5432:5432`; prefer the form above.)

`ADMIN_DISABLED=true` is needed locally because the admin realm validates against a real corporate
Entra tenant and deliberately refuses to start half-configured.

`./gradlew build` needs Docker running: the store tests use TestContainers. Note that
`src/integrationTest` currently contains **no files** — the TestContainers tests
(`RecordingRepositoryTest`, `RecordingJourneyTest`) are in `src/test`. The `integration` task is
still wired into `check` (`build.gradle:101`) and runs as `NO-SOURCE`.

---

## 4. The Helm chart

### Layout

```
charts/amp-try-slc/
  Chart.yaml
  values.yaml
  values.aat.template.yaml
  values.preview.template.yaml
  templates/NOTES.txt
```

The directory name is not free: the pipeline computes `chartName = "${product}-${component}"` and
looks for `charts/<chartName>/values.yaml`, throwing if it is absent
(`vars/helmInstall.groovy:59,66-70`; `src/uk/gov/hmcts/contino/Helm.groovy:18` fixes the `charts`
directory). Product `amp` plus component `try-slc` therefore *requires* `charts/amp-try-slc/`.

### The chart is a thin wrapper over `chart-java`

`Chart.yaml` declares one dependency and almost nothing else:

```yaml
dependencies:
  - name: java
    version: 6.1.1
    repository: 'oci://hmctsprod.azurecr.io/helm'
```

Everything under the `java:` key in `values.yaml` is `chart-java`'s. Your chart exists to set values,
not to template Kubernetes objects; `templates/` holds only `NOTES.txt`.

`Chart.yaml`'s own `version:` matters more than it looks. On a PR the pipeline runs
`enforceChartVersionBumped` (`vars/sectionBuildAndTest.groovy:58-60`), which may auto-bump the chart
version and then abort the build with `AUTO_ABORT` (`vars/enforceChartVersionBumped.groovy:17-19`)
so the bumped commit builds instead. A PR that appears to abort itself once, immediately after you
touched the chart, is this.

### Values that earn an explanation

```yaml
java:
  replicas: 1
  applicationPort: 8080
  image: 'hmctsprod.azurecr.io/amp/try-slc:latest'
  ingressHost: amp-try-slc-{{ .Values.global.environment }}.service.core-compute-{{ .Values.global.environment }}.internal
  aadIdentityName: amp
```

- `applicationPort` must match `server.port` in `application.yaml` and the `EXPOSE` in the
  Dockerfile. `chart-java` defaults it to 4550 (`java/values.yaml:1`), which is wrong for this
  service; the probes are pointed at this port too.
- `image` here is a placeholder. Jenkins overrides it per deploy, and Flux rewrites it in its own
  copy (section 7).
- **`ingressHost` in the base `values.yaml` is effectively dead for the environments you care
  about.** Jenkins deploys overwrite it from `values.<env>.template.yaml`, and Flux overrides it per
  environment. See section 8 — and do not take the `*.internal` names at face value.
- `aadIdentityName: amp` is product-scoped, not component-scoped, because the managed identity it
  names is (`infrastructure/main.tf:37-45`).
- `keyVaults` is deliberately **absent** from the base values and set per environment, because
  sandbox uses a differently-named vault (`amp-try-slc-sbox`) from the `amp-try-slc-<env>` default.

### The template values files

Two files, both consumed by `envsubst` rather than by Helm:

- `values.preview.template.yaml` — `${IMAGE_NAME}` and `${SERVICE_FQDN}` are substituted by the
  pipeline before Helm ever sees the file (`vars/helmInstall.groovy:44-50,77-82`). Those variables
  are set in `helmInstall` from the computed image name and the resolved FQDN, so a preview
  deployment gets its own hostname automatically.
- `values.aat.template.yaml` — same mechanism, but it hardcodes
  `ingressHost: amp-try-slc-staging.aat.platform.hmcts.net`. That is not an arbitrary string: it is
  exactly what the pipeline would compute (section 8), so the two agree.

Both files list the same six Key Vault secrets. The CSI driver fails the **whole** volume mount if
any single listed secret is missing, which presents as pods stuck `FailedMount` and Helm waiting out
its timeout — so the Terraform in section 6 must have run for that environment *before* the chart
lists the secrets.

---

## 5. Jenkins

### The Jenkinsfiles

`Jenkinsfile_CNP` is the whole of the main pipeline:

```groovy
@Library("Infrastructure@2.7.2")

def type = "java"
def product = "amp"
def component = "try-slc"

withPipeline(type, product, component) {
  enableAksStagingDeployment()
  disableLegacyDeployment()
}
```

`product` and `component` are load-bearing everywhere downstream: they name the chart directory, the
ACR repository (`hmctsprod.azurecr.io/amp/try-slc`), the Helm release, the Kubernetes namespace via
`cnp-jenkins-config/team-config.yml:11-20`, and the hostname. Changing either is a cross-repo change,
not a rename.

`enableAksStagingDeployment()` turns on the AKS staging deploy
(`src/uk/gov/hmcts/contino/AppPipelineDsl.groovy:108-110`); `disableLegacyDeployment()` turns off the
old App Service path (`:79-81`). Both are standard for a new service.

`Jenkinsfile_nightly` runs `withNightlyPipeline` on a weekday cron (`H 07 * * 1-5`) against AAT. The
body is empty, and every optional stage in `vars/sectionNightlyTests.groovy` is behind a config flag
that an empty body leaves false — cross-browser, E2E, performance, ZAP security scan, mutation,
Fortify. What actually runs is Checkout, Build, and **Dependency check**
(`builder.securityCheck()`, `sectionNightlyTests.groovy:34-38`). Turning any of the others on is a
line in the body. Note the file pins no library version.

`Jenkinsfile_parameterized` is the **sandbox** job:

```groovy
withParameterizedPipeline('java', 'amp', 'try-slc', params.ENVIRONMENT, 'sandbox') {}
```

This one is important and easy to misread. `withParameterizedPipeline` checks out, builds, and then
calls **only** `sectionDeployToEnvironment` (`vars/withParameterizedPipeline.groovy:102-115`) — the
Terraform section. It never calls `sectionDeployToAKS`. **The sandbox job creates sandbox
infrastructure; it does not deploy the application.** The application in sandbox comes from Flux.

### What the main pipeline does, in order

From `vars/withPipeline.groovy` and `vars/sectionBuildAndTest.groovy`:

| Stage | What it does | Where |
|---|---|---|
| Checkout | Clones; decides whether an image rebuild can be skipped; reads deployment controls | `sectionBuildAndTest.groovy:30-46` |
| Build | `./gradlew assemble`, after writing `build-info.properties` | `GradleBuilder.groovy:25-28` |
| Unit tests and Sonar scan | `./gradlew check`, then SonarQube with a blocking quality gate | `sectionBuildAndTest.groovy:78-102` |
| Security Checks / Tech Stack | OWASP dependency check and tech-stack reporting, in parallel | `sectionBuildAndTest.groovy:103-112` |
| Docker Build | ACR build and push | `sectionBuildAndTest.groovy:114-140` |
| Terraform (plan on PR, apply on master) | `infrastructure/` against AAT, plus a prod plan | `withPipeline.groovy:195-252,269-279` |
| AKS deploy | `helm upgrade --install` of your chart | `sectionDeployToAKS.groovy:71-90` |
| Smoke Test | `./gradlew --rerun-tasks smoke` with `TEST_URL` set | `sectionDeployToAKS.groovy:120-149` |
| Functional Test | `./gradlew --rerun-tasks functional` with `TEST_URL` set | `sectionDeployToAKS.groovy:151-195` |
| Publish Helm chart | Pushes the chart to `hmcts/hmcts-charts` under `stable/<chart>` | `withPipeline.groovy:300-310` |
| Build promotion | Retags the image `prod-<sha>-<timestamp>` in ACR | `sectionPromoteBuildToStage.groovy:46-49` |

On a PR the AKS deploy targets **preview**; on master it targets **AAT**
(`withPipeline.groovy:254-263,289-298`). Functional tests run only on environments in
`functionalTestEnvironments`, which is AAT and preview
(`src/uk/gov/hmcts/contino/Environment.groovy:28`).

### Jenkins deployments are temporary. Flux's are not.

This is the single most useful thing to understand about the pipeline, and nothing in the platform
docs says it.

The release Jenkins installs is named `<chart>-<imageTag>`
(`vars/helmInstall.groovy:137`), where the tag is `pr-<n>` on a PR and `staging` on master
(`src/uk/gov/hmcts/contino/ProjectBranch.groovy:40-45`). So Jenkins deploys `amp-try-slc-pr-123` and
`amp-try-slc-staging` — **never** `amp-try-slc`. `amp-try-slc` is the Flux HelmRelease.

And at the end of every run **that reaches the end**, `sectionDeployToAKS` uninstalls what it
installed (`sectionDeployToAKS.groovy:480-485`):

```groovy
def isOnMaster = new ProjectBranch(env.BRANCH_NAME).isMaster()
if (isOnMaster || !enableHelmLabel) {
  helmUninstall(dockerImage, params, pcr)
}
```

`enableHelmLabel` is true when the PR carries the GitHub label `enable_keep_helm`
(`sectionDeployToAKS.groovy:67-68`). So:

- **master**: the AAT staging release is installed, tested, and torn down, every build.
- **PR**: the preview release is torn down at the end of the build **unless** you add
  `enable_keep_helm` to the PR.

If you want to poke at a preview deployment by hand, add that label before the build you intend to
inspect. (`../README.md:414` says preview environments are "destroyed nightly". What the library
does is uninstall at the end of each build unless `enable_keep_helm` is set; whether a separate
cluster-side nightly sweep of kept releases also exists is **unverified**.)

### The functional-test stage will uninstall your release

Three independent facts combine into one sharp edge.

**One.** `GradleBuilder.functionalTest` calls Jenkins' `junit` step with **no `allowEmptyResults`**
for every product except `civil` (`src/uk/gov/hmcts/contino/GradleBuilder.groovy:118-131`):

```groovy
if (product == "civil") {
  localSteps.junit allowEmptyResults: true, testResults: '**/test-results/functional/*.xml,...'
} else {
  localSteps.junit '**/test-results/functional/*.xml,**/test-results/functionalTest/*.xml'
}
```

`smokeTest` is the same shape with no exception at all (`:93-101`). Zero test results is therefore a
**red stage** — the build fails with `No test report files were found. Configuration error?` after a
perfectly successful deploy. An empty `src/smokeTest` or `src/functionalTest` is not a neutral
choice; it is a broken build.

**Two.** A red functional stage calls `clearHelmReleaseForFailure(...)` and then
`error('Functional test failed')` (`vars/sectionDeployToAKS.groovy:188-191`); the smoke stage calls
the same cleanup at `:136`.

**Three.** `clearHelmReleaseForFailure` uninstalls when the branch is master and
`config.clearHelmReleaseOnFailure` is set, or when it is a PR without `enable_keep_helm`
(`sectionDeployToAKS.groovy:22-27`), and `clearHelmReleaseOnFailure` **defaults to true**
(`src/uk/gov/hmcts/contino/AppPipelineConfig.groovy:36`).

So a red functional run on master uninstalls the Helm release. Write the tests.

Two corrections to the folklore around this, both from the code:

- **There is an opt-out for the uninstall**, though not for the red stage.
  `disableCleanupOfHelmReleaseOnFailure()` sets `clearHelmReleaseOnFailure = false`
  (`src/uk/gov/hmcts/contino/AppPipelineDsl.groovy:125-127`). It does not stop
  `error('Functional test failed')` from failing the build. It does leave the release standing: that
  `error()` throws out of the section before line 480 is reached, so the end-of-section uninstall
  never runs on a failed build. In other words, the end-of-section uninstall is what tears down a
  *successful* run; this flag is what lets you inspect a *failed* one.
- **`nonServiceApp()` is not the answer.** It sets `serviceApp = false`
  (`AppPipelineDsl.groovy:87-89`), which skips the smoke and functional stages
  (`sectionDeployToAKS.groovy:120`) but also skips the whole `if (config.serviceApp)` block in
  `vars/helmInstall.groovy:180-193` — DNS registration, `AKS_TEST_URL`, and the post-install health
  check. The service would have no hostname.

### Why a red functional run also starves Flux

On master, `sectionPromoteBuildToStage` with `stage: PROD` runs **after** `sectionDeployToAKS`
(`vars/withPipeline.groovy:289-341`). That stage is what retags the image as `prod-<sha>-<ts>`
(`vars/sectionPromoteBuildToStage.groovy:46-49`). Flux's ImagePolicy selects on exactly that pattern
(`platform-prs/cnp-flux-config/apps/amp/amp-try-slc/image-policy.yaml`).

So a failing functional test on master does not merely fail a build: it prevents the `prod-` tag
from being created, which means Flux has no new image to reconcile. The deployed service stays on
the previous tag and the failure looks, from the cluster, like nothing happened at all.

Worth knowing in the same breath: the promotion stage itself is **not** gated by environment
approvals — only the Terraform sections are (next section). So for a service with no production
deployment, the prod *Terraform* stage is skipped while the prod *image tag* is still written, which
is exactly what Flux needs.

---

## 6. Infrastructure Terraform

### Layout

```
infrastructure/
  main.tf  state.tf  variables.tf
  sandbox.tfvars  aat.tfvars  demo.tfvars  prod.tfvars
```

The pipeline runs Terraform if, and only if, a directory called `infrastructure` exists
(`vars/sectionDeployToEnvironment.groovy:25`), from inside that directory (`:35`). On a PR it plans,
and only when the PR actually touches `infrastructure/` (`onTerraformChangeInPR`,
`withPipeline.groovy:195`); on master it applies (`withPipeline.groovy:269-279`).

`.terraform-version` at the repo root (`1.16.1`) is read by tfenv on the agent.

### State and backend

`state.tf` declares `backend "azurerm" {}` with no arguments: every setting is supplied by the
pipeline at `terraform init` time (`vars/spinInfra.groovy:63-67`):

```
terraform init -reconfigure \
  -backend-config "storage_account_name=${STORE_sa_name_template}${subscription}" \
  -backend-config "container_name=${STORE_sa_container_name_template}${environment}" \
  -backend-config "resource_group_name=${STORE_rg_name_template}-${subscription}" \
  -backend-config "key=${productName}/${environment}/terraform.tfstate"
```

The practical consequence: state is keyed by product and environment, each environment has its own
state, and you cannot usefully run `terraform init` locally without reconstructing those values.

### Per-environment tfvars

`<environment>.tfvars` is passed with `-var-file` when the file exists
(`vars/spinInfra.groovy:145`). The differences are small and deliberate:

| File | Contents | Why |
|---|---|---|
| `sandbox.tfvars` | `aks_subscription_id`, `pgsql_sku = "B_Standard_B1ms"`, `pgsql_subnet_suffix = "expanded"`, `vault_name = "amp-try-slc-sbox"` | Cheapest Postgres SKU; a distinct vault name so sandbox does not collide with the `amp-try-slc-<env>` default |
| `aat.tfvars` | `aks_subscription_id`, `pgsql_sku = "GP_Standard_D2s_v3"` | Production-shaped SKU for rehearsal |
| `demo.tfvars` | `aks_subscription_id` only | Takes the default SKU |
| `prod.tfvars` | no `aks_subscription_id`, by design | The comment at `infrastructure/prod.tfvars:1-3` records that no prod AKS subscription has been allocated, so a prod apply fails. This service is not deployed to production |

### What it creates

`infrastructure/main.tf` creates, per environment:

- A **component-scoped resource group**, `${product}-${component}-${env}`. The comment at
  `main.tf:24-30` explains why it is not product-scoped: two repos in the same product computing the
  same resource group name from the same expressions means the second Terraform state declares
  resources that already exist, and the apply fails.
- A **Key Vault** via `cnp-module-key-vault`, named from `var.vault_name` when set and
  `${product}-${component}-${env}` otherwise, with `create_managed_identity = true`. The identity
  that module creates is `${product}-${env}-mi` with no component segment — product-wide by
  construction — which is why `main.tf:37-45` warns that exactly one repo per product may set that
  flag.
- A **Postgres flexible server** of its own, `amp-try-flexible-<env>`, with one database
  `tryitnow`, via `terraform-module-postgresql-flexible`. `main.tf:62-66` records the reasoning:
  two repos running Terraform against one server is a state conflict in waiting.
- **Six Key Vault secrets**, all prefixed with the component so they coexist with other components'
  secrets in the same vault: `try-slc-POSTGRES-{USER,PASS,HOST,PORT,DATABASE}` and
  `try-slc-DEMO-SIGNING-KEY-JWK`. These are exactly the six names the chart's
  `values.*.template.yaml` and the Flux patches mount.

The signing key is generated by a `tls_private_key` resource and stored as PKCS#8 PEM — despite the
secret name saying JWK. It must be Terraform-owned and stable: Flux redeploys on every master merge,
and a per-process key would invalidate every token already issued.

### Two different approval gates

They are easy to confuse, and they behave differently.

**Which environments this repo may touch** — `approvedEnvironmentRepository`, wrapping the whole of
`sectionDeployToEnvironment` (`vars/sectionDeployToEnvironment.groovy:23`). It reads
`cnp-jenkins-config/environment-approvals.yml`, and
`src/uk/gov/hmcts/pipeline/EnvironmentApprovals.groovy:27-36` is explicit about the semantics:

```groovy
if (!environmentApprovals.containsKey(environment)) {
  // if environment is not listed in config then assume no whitelist required
  return true
}
```

`environment-approvals.yml` has exactly **one** top-level key, `prod:`. So AAT, demo and sandbox need
no entry at all — they are unlisted and therefore unrestricted — while prod requires the repo to be
named. `try-cp-crime-schedulingandlisting-courtschedule` is not named, by design.

This matters more than it sounds. `infrastructure/variables.tf` declares
`variable "aks_subscription_id" {}` with no default, and `prod.tfvars` deliberately omits it, so a
prod apply *would* fail and take master red with it. It never runs: the gate logs a
"not approved for environment 'prod'" banner and **skips the block without failing**
(`vars/approvedEnvironmentRepository.groovy:15-25`). That is why the prod stage in the stage list is
harmless, and why the master build still reaches the promotion stage that writes the `prod-` image
tag.

**Which Terraform resources this repo may create** — `approvedTerraformInfrastructure`, which fetches
two files from
`cnp-jenkins-config/terraform-infra-approvals/` — `global.json` (required) and `<repo>.json`
(optional, 404 accepted) — and checks every resource type and module source against their union
(`src/uk/gov/hmcts/pipeline/TerraformInfraApprovals.groovy:25-40`).

`global.json` already approves `azurerm_key_vault_access_policy`, `azurerm_key_vault_secret`,
`azurerm_resource_group`, `cnp-module-key-vault?ref=master` and
`terraform-module-postgresql-flexible?ref=master` — everything this repo's `main.tf` uses **except
`tls_private_key`**, which appears in neither `global.json` nor the prepared
`platform-prs/cnp-jenkins-config/terraform-infra-approvals/try-cp-crime-schedulingandlisting-courtschedule.json`.
Three sources disagree about this and none of them is obviously right:

- `infrastructure/main.tf:132-133` says `tls_private_key` "is approved for this repo in
  `terraform-infra-approvals/<repo>.json`" — the prepared JSON does **not** list it.
- `platform-prs/README.md` says the JSON does not include `cnp-module-key-vault` — it does.
- The repo-level JSON may be unnecessary altogether, since `global.json` covers the rest.

**Unverified**: whether a `tls_private_key` resource is actually rejected. On the library version
read, a non-approved plan is a **warning, not a hard failure**: `approvedTerraformInfrastructure`
logs the unapproved resources, raises a pipeline deprecation warning, and then calls the block
anyway (`vars/approvedTerraformInfrastructure.groovy:20-53`). To confirm, run the master build and
read the "Build Infrastructure - aat" stage log for the `not-approved-infra` banner.

---

## 7. The other repos you must raise PRs against

Two platform repos, owned by other teams. Nothing deploys until both have merged.

Prepared, reviewable copies of every file live under [`../platform-prs/`](../platform-prs/), so the
change is a copy rather than a transcription. **Whether each has been raised or merged for this
service is tracked in [`../CNP-ONBOARDING-PLAN.md`](../CNP-ONBOARDING-PLAN.md)**, not here — and not
in `platform-prs/README.md`, whose "nothing here has been pushed" line is a status claim with a
shelf life. Check the tracker before assuming either state.

### `hmcts/cnp-jenkins-config`

| File | Change | Effect |
|---|---|---|
| `deployment-controls.yml` | Add `- repo: https://github.com/hmcts/<repo>.git` with `deployment-enabled: true` under the top-level `repositories:` key | Without it `sectionBuildAndTest` returns `deploymentEnabled = false` (`:34`) and `withPipeline.groovy:107` skips every deploy section. The build goes green having deployed nothing |
| `terraform-infra-approvals/<repo>.json` | Only if you use a resource type or module `global.json` does not cover | See section 6 |

The file is alphabetical by repo URL *by convention, not invariant* — four entries at the end are
deliberately out of order, and `platform-prs/cnp-jenkins-config/deployment-controls.snippet.yml`
records the verified insertion point (between `test-spring-java-upgrade` and `ts-translation-service`
as of 5 October 2026). Do not "fix" the out-of-order tail.

Product-level registration — namespace, Slack channels, tags — lives in the same repo's
`team-config.yml`. `amp` is already there (`team-config.yml:11-20`: namespace `amp`, contact
`#api-marketplace-tech`, build notices `#api-marketplace-build-notices`). A genuinely new *product*
would need that entry too; a new *component* of an existing product does not.

### `hmcts/cnp-flux-config`

A new directory `apps/<product>/<component>/` with five files, plus one line added to each of four
existing kustomizations.

| File | Purpose |
|---|---|
| `amp-try-slc.yaml` | The `HelmRelease`. Carries the `#{"$imagepolicy": ...}` marker Flux rewrites, and points `chart: ./stable/amp-try-slc` at the `hmcts-charts` GitRepository |
| `image-repo.yaml` | `ImageRepository` for `hmctsprod.azurecr.io/amp/try-slc` |
| `image-policy.yaml` | `ImagePolicy` selecting the newest `prod-<sha>-<ts>` tag, numerically ascending on the timestamp |
| `sbox.yaml`, `aat.yaml`, `demo.yaml` | Per-environment `HelmRelease` patches: ingress host and vault mounts |

Image policies are normally generated rather than written. The script lives at
`bin/v2/add-image-policies.sh` in `cnp-flux-config` (not at the repo root, as
`platform-prs/README.md` implies):

```bash
./bin/v2/add-image-policies.sh amp amp try-slc hmctsprod
```

The trailing `hmctsprod` is required — the script still defaults to `hmctspublic`, which is the
wrong registry and produces a policy that never matches a tag.

#### Flux kustomize path depth matters, and a wrong one fails silently

The entry point is `apps/<product>/base/kustomize.yaml`, which points Flux at
`./apps/<product>/${ENVIRONMENT}/base` (`cnp-flux-config/apps/amp/base/kustomize.yaml:7`). From
there, two different layouts exist in the repo and **mixing them deploys nothing**.

The `amp` layout — and its direct precedent `apim`, which is the same team's existing product — puts
the HelmReleases in the product's shared base, and each environment's base includes that:

```
apps/amp/base/kustomization.yaml       resources: ../../base, ../../base/workload-identity,
                                                  ../amp-try-slc/amp-try-slc.yaml
apps/amp/sbox/base/kustomization.yaml  resources: ../../base            <- apps/amp/base
                                       patches:   ../../amp-try-slc/sbox.yaml
```

From `apps/amp/sbox/base`, `../../base` resolves to **`apps/amp/base`** — the product base, which
includes the HelmRelease. `../../../base` would resolve to **`apps/base`**, which is platform
plumbing only: namespace, service account, alerts, provider
(`cnp-flux-config/apps/base/kustomization.yaml`). It is a perfectly valid kustomization that builds
cleanly and contains none of your application. Nothing errors; nothing deploys.

Compare `apps/apim/sbox/base/kustomization.yaml`, which uses `../../base` the same way, with
`apps/rpe/sbox/base/kustomization.yaml`, which uses `../../../base` **and** lists each HelmRelease
explicitly in that environment's base. Both work. Taking the depth from `rpe` without also taking
its explicit HelmRelease list is the failure mode.

Note the two kinds of edit, too: the shared product base lists **HelmRelease paths under
`resources`**, while each environment's base lists **patch paths under `patches`**. Adding a patch
under `resources` or a HelmRelease under `patches` does not error either.

**No preview patch.** `apps/amp/preview/base/kustomization.yaml` references `../../../base` and
`../../../base/workload-identity` only — by design, because PR environments are deployed by Jenkins,
not reconciled by Flux. Do not add one.

#### Seed a zeroed image tag, because the real one cannot exist yet

`cnp-flux-config/tests/flux-v2-image-automation.sh` validates every HelmRelease image string against
`prod-[a-f0-9]+-(?P<ts>[0-9]+)` (`:97,170,218-220`). `:latest` can never pass that check.

For a brand-new component this is circular: the first `prod-<sha>-<ts>` tag is written by
`sectionPromoteBuildToStage`, which runs on master *after* the deploy that the Flux PR is what
enables. There is no tag to reference when you raise the PR.

Seed a placeholder of the right shape with an all-zero timestamp:

```yaml
image: hmctsprod.azurecr.io/amp/try-slc:prod-0000000-00000000000000 #{"$imagepolicy": "flux-system:amp-try-slc"}
```

The check validates only the *shape*, and the ImagePolicy sorts numerically ascending on the
timestamp, so an all-zero timestamp loses to every real tag and image automation rewrites the line as
soon as the first prod image is pushed. It self-heals. Only the base `<component>.yaml` needs it;
environment overlays declare no `image:`. This is an inference from how the check and the policy
work rather than something the platform docs sanction, so say so on the PR.

(The copy in `platform-prs/cnp-flux-config/apps/amp/amp-try-slc/amp-try-slc.yaml` still carries
`:latest`, which the check would reject.)

#### One more ordering constraint

The HelmRelease points at `chart: ./stable/amp-try-slc` in the `hmcts-charts` GitRepository. That
path is created by the pipeline's "Publish Helm chart" stage, which rsyncs your chart into
`hmcts/hmcts-charts` under `stable/<chart-name>`
(`vars/helmPublish.groovy:32-33` → `resources/uk/gov/hmcts/helm/push-helm-charts-to-git.sh:39`).
Until a master build has run that stage, Flux has no chart to install.

### The order that works

1. GitHub repo, topic, branch protection, Renovate.
2. `deployment-controls.yml` PR to `cnp-jenkins-config`. Merge before expecting any deploy.
3. Run the sandbox `Jenkinsfile_parameterized` job — Terraform only. This creates the vault, the
   Postgres server and the six secrets in sandbox.
4. First master build. This publishes the chart to `hmcts-charts` and pushes the first
   `prod-<sha>-<ts>` image.
5. `cnp-flux-config` PR, with the zeroed placeholder tag. Flux reconciles and the sandbox deployment
   appears.

Steps 3 and 5 are the ones people invert. If the Flux patch lists Key Vault secrets that Terraform
has not created yet, the CSI driver fails the whole mount and the pods sit `FailedMount`.

### A note on the clones used here

The `cnp-flux-config` clone read for this guide is `master` at `596ee0520c9`, 7 October 2026. At that
commit `apps/amp` contains only the five skeleton kustomizations and **no** component directories —
the `amp-marketplace*` components named in `platform-prs/README.md` and
[`../CNP-ONBOARDING-PLAN.md`](../CNP-ONBOARDING-PLAN.md) 4.1 are not there, and `apps/apim` is where
the equivalents live. Both repos move daily. **Apply the marked line to whatever the file says at the
time; never copy a snapshot of someone else's kustomization over the top**, or you silently revert
whatever landed since.

---

## 8. DNS, and how the URL comes to exist

Three different mechanisms produce three different hostnames, and they are routinely conflated.

### The pipeline's hostname

`vars/helmInstall.groovy:30-33` resolves a hostname before deploying:

```groovy
EnvironmentDnsConfigEntry dnsConfigEntry = new EnvironmentDnsConfig(this).getEntry(params.environment, product, component)
AzPrivateDns azPrivateDns = new AzPrivateDns(this, params.environment, dnsConfigEntry)
String serviceFqdn = azPrivateDns.getHostName(aksServiceName)
```

- `aksServiceName` is `<product>-<component>-<imageTag>`
  (`src/uk/gov/hmcts/contino/DockerImage.groovy:76-79`), and `imageTag` is `pr-<n>` on a PR,
  `staging` on master (`ProjectBranch.groovy:40-45`).
- The zone comes from `cnp-jenkins-config/private-dns-config.yml`, whose `zoneTemplate` is
  `${environment}.platform.hmcts.net` for both subscriptions (`:3,13`).

So the pipeline's hostnames are:

| Branch | Environment | Hostname |
|---|---|---|
| `PR-123` | preview | `amp-try-slc-pr-123.preview.platform.hmcts.net` |
| `master` | aat | `amp-try-slc-staging.aat.platform.hmcts.net` |

Which is exactly what `charts/amp-try-slc/values.aat.template.yaml` hardcodes, and what
`values.preview.template.yaml` receives through `${SERVICE_FQDN}`.

The record itself is an **A record, not a CNAME**. `helmInstall.groovy:181-183` calls
`azPrivateDns.registerDns(aksServiceName, ingressIP)` when no CNAME already exists, and that method
runs `az network private-dns record-set a create` / `add-record` / `update`
(`src/uk/gov/hmcts/contino/AzPrivateDns.groovy:83-88`) pointing at the Traefik ingress controller's
load-balancer IP (`helmInstall.groovy:42`). `checkForCname` (`AzPrivateDns.groovy:25-52`) exists only
to detect a pre-existing CNAME, in which case registration is skipped and
`--set global.disableTraefikTls=true` is passed instead (`helmInstall.groovy:119`).

That whole block is inside `if (config.serviceApp)` (`helmInstall.groovy:180`) — which is why
`nonServiceApp()` is not an escape hatch from the test stages: it also removes the hostname.

**Unverified**: `private-dns-config.yml:25-26` marks the `preview` environment `active: false`, which
makes `checkForCname` return early, but `registerDns` does not read `active` in the version read. To
confirm whether a preview A record is actually written, read the "AKS deploy - preview" stage log for
the `Registering DNS for ...` line.

### Flux's hostname

Flux does not use any of the above. Each environment patch sets `ingressHost` outright:

```yaml
# platform-prs/cnp-flux-config/apps/amp/amp-try-slc/sbox.yaml
java:
  ingressHost: amp-try-slc.sandbox.platform.hmcts.net
```

The pattern is `<release>.<env>.platform.hmcts.net`. Note the environment segment is the *zone* name
`sandbox`, while the Flux directory is `sbox` — they are not the same word.

### The `*.internal` names are stale

`charts/amp-try-slc/values.yaml` and the deployment table at `../README.md:411-416` both give
`amp-try-slc-{env}.service.core-compute-{env}.internal`. **Do not rely on these.** The pipeline
computes `*.platform.hmcts.net` from `private-dns-config.yml`, Flux sets `*.platform.hmcts.net`
explicitly, and the library itself raises a deprecation warning when a resolved FQDN ends in
`.internal` (`helmInstall.groovy:35-37`). The base-values host survives only where neither a
`values.<env>.template.yaml` nor a Flux patch overrides it.

### Why you need the VPN

Every zone in `private-dns-config.yml` is an **Azure *private* DNS zone**, in the resource group
`core-infra-intsvc-rg` (`:10,33`), and the records are managed with `az network private-dns`
(`AzPrivateDns.groovy:83-88,93`). Private zones resolve only from inside the networks they are linked
to. That is the verified part, and it covers sandbox, AAT and demo alike — they are all in the same
private list (`:7,21-22`).

The team reaches those networks over the **F5 VPN**; the new-component page lists "VPN access" as a
prerequisite without naming a product. Connect first, then resolve. A `curl` that fails at DNS
resolution rather than at TLS or HTTP is almost always a disconnected VPN, not a broken deployment.

---

## 9. Testing

The ladder, from fastest to most expensive. Each rung catches a class of failure the one below
cannot.

| Rung | Command | Runs where | Catches |
|---|---|---|---|
| Local run | `./gradlew bootRun` | your machine | "does it start at all" |
| Unit | `./gradlew test` | agent, no deployment | logic, auth rules in-process |
| Integration | `./gradlew integration` (wired into `check`) | agent, Docker required | persistence, via TestContainers |
| Smoke | `./gradlew smoke` with `TEST_URL` | agent, against a deployed URL | "is the deployment alive and are the probe paths open" |
| Functional | `./gradlew functional` with `TEST_URL` | agent, against a deployed URL | the consumer journey through the real ingress and real secrets |
| Preview | the PR build | preview AKS | everything above, on a per-PR deployment |
| Sandbox by hand | `curl` over the VPN | sandbox AKS | that Flux, Terraform and the chart agree |

### Local

```bash
docker run -d --name tin-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=tryitnow \
  -p 55432:5432 postgres:16-alpine

POSTGRES_PORT=55432 ./gradlew build
POSTGRES_PORT=55432 ADMIN_DISABLED=true ./gradlew bootRun
```

Then, in another shell, the smoke suite against your own process — the suites default to
`http://localhost:8080` when `TEST_URL` is unset
(`src/smokeTest/java/uk/gov/hmcts/cp/SmokeTest.java`, `src/functionalTest/.../FunctionalTest.java`):

```bash
./gradlew smoke
./gradlew functional
```

This is worth doing before the first pipeline run, because a test suite that passes locally and
produces no results on the agent is still a red stage.

### Smoke

Keep smoke tests to liveness. This repo's has exactly two assertions, and the second one exists
because of a real incident: `/health` answers 200 unauthenticated, and `/health/liveness` — the path
`chart-java` actually probes — does too.

### Functional

The functional suite is the consumer journey: read `/scenarios`, exchange demo credentials for a
token at `/oauth2/v2.0/token`, call the API, and take both documented refusals (401 for a bad
secret, 403 for the role-less client).

Two design rules in `FunctionalTest.java` generalise to any service:

- **Assert on shape, never on fixture values.** A brittle assertion costs a deployment, not just a
  build (section 5).
- **Never index into a collection whose order is not guaranteed.** `ScenarioCatalog` iterates a
  `Map.copyOf` map, whose iteration order the JDK randomises per JVM run, so "the first 200 scenario"
  is a different fixture on different pods. The suite filters by published status and exercises
  every match.

### Preview

Preview is the PR path and needs no extra configuration — `withPipeline.groovy:254-263` deploys every
PR to preview and runs smoke and functional against it. The URL is
`https://amp-try-slc-pr-<n>.preview.platform.hmcts.net`, shown in the build log as *"Your AKS service
can be reached at: ..."* (`helmInstall.groovy:187`) and attached to the PR as a GitHub deployment
(`sectionDeployToAKS.groovy:84-86`).

Add the `enable_keep_helm` label to the PR **before** the build if you want the environment to
survive for manual poking; otherwise it is uninstalled when the build ends.

### Verifying sandbox by hand

This is the finish line for a new service. Connect to the VPN first.

```bash
BASE=https://amp-try-slc.sandbox.platform.hmcts.net

# 1. DNS resolves at all - if this fails, the VPN is down or the Flux PR has not merged
dig +short amp-try-slc.sandbox.platform.hmcts.net

# 2. The probe paths, unauthenticated. All three must answer 200
curl -sS -o /dev/null -w '%{http_code} %{url_effective}\n' $BASE/health
curl -sS -o /dev/null -w '%{http_code} %{url_effective}\n' $BASE/health/liveness
curl -sS -o /dev/null -w '%{http_code} %{url_effective}\n' $BASE/health/readiness

# 3. The health body
curl -sS $BASE/health
```

A healthy response contains `"status":"UP"`. A 401 on any of the three means the actuator paths are
no longer exempt from token validation, and the pod will not stay ready.

Then the service's own entry point, which is unauthenticated by design:

```bash
curl -sS $BASE/scenarios
```

If `/health` answers but `/scenarios` does not, the application is up but is not serving what it
should — check the pod logs.

If nothing resolves, work backwards: is the VPN up; has the `cnp-flux-config` PR merged; did the
sandbox Terraform job run (no vault means no secrets means `FailedMount`); is there a `prod-` tagged
image in ACR for the ImagePolicy to select.

### What comes after sandbox

Out of scope here, one line each:

- **AAT** — reached by merging to master; Jenkins deploys `amp-try-slc-staging` for the test stages
  and tears it down, while Flux reconciles the durable `amp-try-slc` from `apps/amp/aat/base`.
- **Demo** — the `demo.yaml` Flux patch; for this service demo is the last environment and there is
  deliberately no production deployment. See [`../CLAUDE.md`](../CLAUDE.md).
- **APIM registration** — publishing the public contract is separate work and is not part of this
  guide.
- **Path to Live** — environment approvals, OAT and shuttering are covered by the platform's
  [Path to Live](https://hmcts.github.io/cloud-native-platform/path-to-live/) pages.

For completeness: the master pipeline also runs a prod Terraform stage
(`withPipeline.groovy:312-322`), gated by `environment-approvals.yml` in `cnp-jenkins-config`. You
will see it in the stage list even for a service that is never deployed to production;
`infrastructure/prod.tfvars` deliberately omits `aks_subscription_id`.

---

## 10. Troubleshooting

### Helm sits for forty minutes and then fails

The timeout structure is nested: `sectionDeployToAKS.groovy:74` wraps the install in
`timeoutWithMsg(time: 40, unit: 'MINUTES', action: 'Install Charts to AKS')`, inside which
`Helm.installOrUpgrade` runs `helm upgrade --install --wait --timeout 1250s`
(`src/uk/gov/hmcts/contino/Helm.groovy:512`; `--wait` is added on non-PR branches only) and retries
up to three times (`helmInstall.groovy:153-172`).

So "it hung for forty minutes" means the pods never became ready. The two usual causes:

- **The wrong health path is exempt from authentication.** `chart-java` probes `/health/liveness` and
  `/health/readiness` (section 3). Exempting only `/health` leaves the probes answering 401 forever.
- **A Key Vault secret listed in the chart does not exist.** The CSI driver fails the *whole* volume
  mount if any one listed secret is absent, so the pod never starts. Pods show `FailedMount`. Run the
  environment's Terraform before the chart lists the secret.

### `No test report files were found. Configuration error?`

A test task ran `NO-SOURCE`. `GradleBuilder.smokeTest` and `functionalTest` call Jenkins' `junit`
step with no `allowEmptyResults` (`GradleBuilder.groovy:99,128`), so an empty `src/smokeTest` or
`src/functionalTest` fails the build *after* a successful deploy — and then uninstalls the release.
Write the test. See section 5 for why `nonServiceApp()` is not the fix.

### The Helm release vanished after a failed test

Expected. See section 5: `clearHelmReleaseForFailure` plus `clearHelmReleaseOnFailure = true` by
default, and an unconditional uninstall at the end of the section. On a PR, add `enable_keep_helm`.

### Flux shows the component as reconciled but nothing is deployed

Almost always a kustomize path. Check that your environment's `base/kustomization.yaml` includes
`../../base` (the **product** base, which lists HelmReleases) and not `../../../base` (the platform
base, which does not) — unless you have also listed the HelmRelease explicitly in that environment,
as `apps/rpe` does. Section 7.

The other silent failure is adding a patch path under `resources`, or a HelmRelease path under
`patches`. Both build cleanly and do nothing.

### Flux never picks up a new image

The ImagePolicy matches `^prod-[a-f0-9]+-(?P<ts>[0-9]+)` only. `:latest` never matches. For a new
component, seed `:prod-0000000-00000000000000` (section 7). For an existing one, check that the
master build reached "Prod build promotion" — a red functional stage stops the pipeline before the
retag.

### Intermittent 401s after a deploy

`DEMO_SIGNING_KEY_JWK` is not set, so each pod generates its own ephemeral key and tokens issued by
one pod are rejected by another. Terraform owns that secret
(`tls_private_key` in `infrastructure/main.tf`); if it is missing, the apply has not run for that
environment.

### `role "postgres" does not exist` locally

A native Postgres on `127.0.0.1:5432` is shadowing the container. Publish the container on 55432 and
run with `POSTGRES_PORT=55432`. Section 3.

### Jenkins logs `repository "<product>/<component>" is not found` during Checkout

Benign. `Acr.hasRepoTag` runs `az acr repository show-tags` only to decide whether an image rebuild
can be skipped, and swallows the failure; Jenkins prints the step failure before Groovy catches it.
Expected until the first build pushes an image, and again after any product or component rename.

### The build is green but nothing deployed

`deployment-controls.yml` is missing the repo. `sectionBuildAndTest.groovy:34` computes
`deploymentEnabled`, and `withPipeline.groovy:107` skips every deployment section when it is false.
Section 7.

### A PR aborts itself immediately after a chart change

`enforceChartVersionBumped` auto-bumped `charts/<chart>/Chart.yaml` and raised `AUTO_ABORT`
(`vars/enforceChartVersionBumped.groovy:17-19`). The bumped commit builds next; nothing is wrong.

### More

The repo-specific debugging table in [`../CLAUDE.md`](../CLAUDE.md) covers the application-level
failures — CORS, the two auth realms, contract drift, the OpenAPI generator leak.

---

## What is not verified in this document

- Jenkins library citations were read at tag **2.12.2**. The ones this guide leans on were
  re-checked against **2.7.2**, the tag `Jenkinsfile_CNP:3` actually pins, and the line numbers are
  identical there: `AppPipelineDsl.groovy:121,125-126` (the cleanup flag and its opt-out),
  `GradleBuilder.groovy:99,126,128` (the `junit` calls), `AzPrivateDns.groovy:77,85` (the A record)
  and `withParameterizedPipeline.groovy:96` (the single `sectionDeployToEnvironment` call). Other
  citations in this document were **not** re-checked at 2.7.2.
- Whether `tls_private_key` is actually refused by the Terraform approvals gate, and whether a
  non-approved plan warns or fails in **2.7.2**. In 2.12.2 it warns.
- Whether a preview A record is written, given `active: false` for the preview zone.
- "F5" as the VPN product is not named by the platform docs, which say only "VPN access". It is
  corroborated outside these files: the client's posture check reports itself as F5 BIG-IP APM with
  an OPSWAT OESIS build. What is verified from files is that the zones are Azure *private* DNS zones
  and therefore need network access to resolve.
- Whether a cluster-side nightly sweep of `enable_keep_helm` releases exists, separately from the
  end-of-build uninstall the library performs.
- The Dockerfile's `ARG APP_INSIGHTS_AGENT_VERSION` and the base image's entrypoint. The contents of
  `hmctsprod.azurecr.io/base/java` were not read.
- The exact `prod-<sha>-<timestamp>` tag format. It is inferred from the ImagePolicy's regex and from
  a live HelmRelease (`apps/rpe/rpe-service-auth-provider/rpe-service-auth-provider.yaml`);
  `Acr.retagForStage`, which writes it, was not read.
- `check-helm-version-bumped.sh`, which `enforceChartVersionBumped` runs, was not read — only the
  Groovy that invokes it and interprets its exit status.
- The `cnp-flux-config` and `cnp-jenkins-config` clones are `master` as of 7 October 2026. Both
  repos change daily; re-read before copying anything.

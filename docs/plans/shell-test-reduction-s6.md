# S6 focused CI: retained requirements and selection audit

Issue [#203](https://github.com/WenHsuanYu/SimpleMatch/issues/203) owns focused business CI and
final program acceptance. Parent [#196](https://github.com/WenHsuanYu/SimpleMatch/issues/196)
defines AC-01 through AC-12. This record applies the 2026-11-02 owner amendment.
It records the P0 requirement review, the bounded P1 workflow corrections,
the source-aligned complete-local PASS, and subsequent focused hosted attempts.
It does **not** assert final S6 acceptance while the remaining gates are open.

The implementation baseline is `ececf38c2297b89738b2f039fa6e81cf3bd99d81`.
The S5 complete-local PASS belongs to executable source
`1a3fa704f37873acacab54415ce794171fd4456a`.
The four changes between these commits are Markdown records. See the
[final S5 evidence](shell-test-reduction-s5.md#2026-11-09-final-s5-p6-evidence-and-handoff).

## P0: authoritative requirement owners

**Complete local** means `./gradlew verifyAll` with the retained `resting-buy`
scenario. **Selected CI** means only the tests actually invoked by each workflow.
One owner can prove several requirements within one deployment. A component test
cannot establish the additional behavior observed only in a deployed scenario.

| Retained requirement | Authoritative behavior and test owner | Existing hosted selection | Coverage boundary |
| --- | --- | --- | --- |
| R11, durable critical delivery during a PostgreSQL outage | `KubernetesCriticalDelivery` and `KubernetesConsumerRestart` in `RestingBuyKubernetesTest` | Java and CDC component checks; no deployed R11 job | Retain actual outage, rejection, offsets, eventual delivery and cleanup. Retire the older simultaneous consumer-Pod outage matrix. |
| R12, Gateway admission and close | `KubernetesGatewayAdmission` and `KubernetesTradingClose` in the same live test; Gateway policy tests | Java `test` and QuickFIX `certificationTest` | Component checks do not prove deployed Close Barrier completion. |
| R13, public FIX resting BUY | `RestingBuyKubernetesTest` FIX submission, durable SQL and singular Matching/Persistence/Account assertions | Java service and QuickFIX tests only | No hosted public FIX-to-Matching deployment claim. |
| R14, same-owner Gateway restart | `KubernetesGatewayRecovery` in `RestingBuyKubernetesTest`; Gateway WAL/session tests | Java and QuickFIX tests | Retain actual session recovery and singular delivery. Retire the duplicate Shell recovery runner. |
| R15, Matching replacement and replay | `KubernetesMatchingRecovery` in `RestingBuyKubernetesTest`; native replay and service inbox tests | Native CI and Java tests | Native replay does not prove a deployed replacement. Controlled duplicate delivery does not imply the restart caused redelivery. |
| R16, Market Data rebuild | `KubernetesMarketDataRebuild` through `KubernetesMarketData` in `RestingBuyKubernetesTest`; existing evidence verifier | Projection and Query Java tests | Service tests do not prove deployed Redis outage, rebuild and repaired public snapshot. |
| R17, public market-data stream recovery | `KubernetesMarketDataStreamer` through the same live owner; existing stream observer | Streamer Java tests | No hosted public gRPC disconnect and resubscription claim. |
| R18, authenticated Query and replay | `KubernetesQueryBusinessRead`, `KubernetesQueryExecution`, `KubernetesQueryActiveProcessing` and `KubernetesQueryReplay` in `RestingBuyKubernetesTest`; Query HTTP/JDBC tests | Java and Query Redis TLS/ACL tests | Hosted components do not prove real FIX crossing, zero-Query-Pod isolation or source-bound Query replay. |
| R19, Query policy replacement | `KubernetesQueryPolicyReplacement` in `RestingBuyKubernetesTest`; Query authorization tests | Java Query tests | Hosted components do not prove live old-socket fencing, two new replicas or Account Kafka catch-up. Synthetic Account lifecycle fixtures remain identified as synthetic. |
| R20, Outbox CDC and exact redelivery | `OutboxCdcIntegrationTest` through `verifyCdc`, backed by Testcontainers | CDC CI `verifyCdc` | Retired Kubernetes Risk CDC telemetry and Connect worker reassignment are intentional losses. This owner does not restore them. |
| R21, Query health during Redis loss | `KubernetesQueryHealth` through `KubernetesMarketData` in the resting-buy run; Query HTTP tests | Java and Redis TLS/ACL tests | Hosted HTTP tests do not prove both deployed Query Pods remained healthy during the outage. |
| R22, Matching crash/recovery and native integrity | `KubernetesMatchingCrash` and `KubernetesMatchingRecovery` in the resting-buy run; `nativeTest` CTest | Native CI and Java/Matching components | Retired whole-fleet, CPU-pinning and independent worker-stop benchmark claims stay retired. |

The other S0 requirements also have explicit dispositions. Their owners
remain distinct from the R11-R22 deployed business assertions:

| Requirement | Retained owner and CI selection | Scope decision or intentional retirement |
| --- | --- | --- |
| R01, rendered manifests | `manifestCheck` in Java, CDC and complete-local Gradle tasks | Pinned schema and necessary project constraints replace old static overlay snapshots; real installation still requires Kubernetes. |
| R02, Flyway and PostgreSQL | `verifyPostgres`, service/JDBC tests, Flyway CI | Retain versioned migrations and representative SQL checks. Retire redundant Shell Flyway wrappers and H2-based acceptance. |
| R03, transaction and Account v2 | Account/Risk Spring transaction, saga, gRPC and persistence tests in Java CI | Retire checklist-text and completed v1/v2 source-location checks, without retiring transactional safety. |
| R04, Matching topic safety | Retained topic validator/provisioner, native messaging checks and deployment configuration owners | Preserve RF/ISR, partition and producer contracts. Retire old cutover fixture matrices and static script-shape guards. |
| R05, durable outbox publication | `verifyCdc` and its `OutboxCdcIntegrationTest` in CDC CI | Preserve actual event identity and redelivery. Retire legacy Compose connector adapters and duplicate Shell CDC observers. |
| R06, source-bound images and runtime content | `KubernetesBuildInputs`, `KubernetesImages`, Java image integrity tests and narrow image transport Shell contracts | Preserve actual kind-node execution and digest checks. Retire duplicate build/report orchestration. Hosted business image-transfer measurements remain for P3. |
| R07, owned kind lifecycle | `VerificationRun`, `KindEnvironment`, ownership Jupiter tests and lifecycle CI contracts/smoke | Preserve exact cleanup, diagnostics and foreign-resource safety. No standalone historical lifecycle PASS reuse. |
| R08, resource accounting | `manifestCheck` for declarations and retained local-resource report operations | The owner retired the numeric host-capacity preflight and its Ruby calculator. Neither manifest requests nor prior RSS measurements prove hosted feasibility. P3 must measure the selected CI job. |
| R09, complete run selection and verdict | `verifyAllScope`, `verifyAll`, Jupiter XML and run-owned Kubernetes summary | Retire the 62-phase dispatcher, report adapters, synthetic PASS reuse and historical phase schema. |
| R10, focused diagnostics | Retained bounded run-owned diagnostics, `verifyCdc` and narrowly scoped operator tools | Retire the old CDC continuation, Risk CDC observer and standalone Java placement collector. No focused report may upgrade a complete-local verdict. |
| R23, external PostgreSQL | Explicit operator `postgresLiveTest`; local disposable DB verification is `verifyPostgres` | External read-only checks remain opt-in. Local CI does not certify a production PostgreSQL server. |
| R24, external FIX | QuickFIX `certificationTest` in Java CI, explicit external FIX operator task | External counterparty/session certification remains opt-in. Simulator and local FIX results do not prove an external exchange. |
| R25, fast local hooks and static analysis | Retained narrow pre-commit/Flyway checks and Java `staticAnalysis` | No expensive pre-push hook or new analysis rules are introduced. |
| R26, historical checklist shape | Retired archive checkers and their CI caller | No current independent behavioral requirement. R03 transaction invariants remain tested at their authoritative seams. |

The smaller `risk-command` live owner is `RiskCommandKubernetesTest` through
`verifyKubernetes -PkubernetesScenario=risk-command`. It checks accepted Risk
admission, durable outbox identity and exact Kafka payload bytes. It does not
run Matching, the public FIX Gateway or Query. S6 P2 selects it in a
disposable hosted kind job. It must not be labeled as R11-R19 or full-local
certification. Hosted feasibility and complete outcome remain subject to
the actual selected job run and P3 resource measurements.

## P0: retirement and remaining Shell responsibilities

The [S0 170-file inventory](shell-test-reduction-s0/script-ledger.md) records
the original file-level dispositions. The [S5 final retirement groups](shell-test-reduction-s5.md#2026-11-06-p5b-coherent-operator-and-aggregate-retirement)
record the deleted callers, fixtures, duplicate implementations and deliberate
verification losses. These decisions remain in force:

| Responsibility group | Decision | Remaining owner or intentional loss |
| --- | --- | --- |
| Old R11-R15 Shell business runners, fixture matrix and Ruby verdicts | Retired with their dedicated callers and contracts | Live Jupiter owner and service tests; no separate all-partition or simultaneous-Pod-failure certification |
| R16-R19 Shell/Python Query and Market Data Labs, collectors and compatibility result formats | Retired with their dedicated consumers | Existing Java live owner and focused HTTP/JDBC/evidence tests; no second policy-approval authority or legacy read-matrix reporter |
| R20 Kubernetes Risk CDC observer and Connect task reassignment | Intentionally retired | `verifyCdc` tests real broker/connector publication contracts; deployed Connect reassignment and Risk CDC telemetry are no longer tested |
| R22 extra E2E and native capacity wrappers | Retired | Existing Native CTest and live Matching owner; no new throughput, pinned-CPU or production HA claim |
| Former 62-phase full-local runner, historical PASS reuse and static source-shape checks | Retired with phase adapters, wrappers and dedicated tests | Gradle `verifyAll`, `verifyAllScope`, service tests, Testcontainers, CTest and live Jupiter; no old phase IDs or Compose-only PASS |
| Remaining Shell scripts | Retain narrow, actively consumed host operations | Local image, kind, resource, Flyway/topic and operator operations. The lifecycle CI Shell contracts test these utilities, not trading behavior. |

The tracked inventory at P0 is **34 `.sh` files / 6,896 physical lines**.
The newline count is 6,895 because one final line has no newline.
The frozen baseline is **170 files / 34,923 lines**. No tracked `.py` or
`.rb` file remains. The current count meets both 80% design limits:
34 files and 6,984 physical lines. The original reductions came from deleting
obsolete responsibilities, not moving Shell into YAML, Gradle or Java strings.
The P1 changes add no business logic, interpreter program or test implementation.

## P0/P1: CI selection and ownership

| Workflow | Retained task or operation | Current selection and S6 action |
| --- | --- | --- |
| `ci-java.yml` | `manifestCheck`, `staticAnalysis`, `test`, Gateway `certificationTest`, Query `redisSecurityTest` | Push, PR and manual; P1 adds `services/market-data-projection/**` to both event path filters. |
| `ci-cdc.yml` | `manifestCheck`, `verifyCdc` | Push, PR and manual for CDC, shared contracts and dependency infrastructure. The old CDC continuation is not invoked. |
| `ci-flyway.yml` | Dependency contracts and `verifyPostgres` | Push, PR and manual for database-owning services, SQL fixtures and Gradle infrastructure. Includes Projection already. |
| `ci-native.yml` | Native CMake build and CTest | Push, PR and manual for Matching, CMake, protocol and native fixtures. |
| `ci-local-resource-lifecycle.yml` | Existing Shell utility contracts and kind registry/resource smoke | PR and manual only. P1 bounds the `contracts` job. Neither job executes a deployed business scenario. |
| `ci-business-kind.yml` | Existing `verifyKubernetes` → `RiskCommandKubernetesTest` | P2 selects the deployed Risk/Account/outbox/Kafka scenario on push, PR and manual dispatch; source and shared build/deployment inputs are included. The runner has an isolated disposable kind cluster. No other deployed scenario is claimed. |
| `dependency-submission.yml` | Dependency graph submission | Push and manual; it is not a business assertion owner. |

The current service, dependency and native workflows already run separately.
Splitting the remaining fifteen-owner Kubernetes `resting-buy` topology into
jobs would change the behavior under test. The [approved CI scope decision](verification-ci-scope-decision.md)
therefore makes complete hosted CI optional. P2 must add push-selected focused
business kind coverage through the existing Gradle owner. Source, shared
infrastructure, workflow and test changes must trigger all affected retained
selected suites. P2 must test its final path filters on GitHub.

## P2: selected hosted business kind execution

The new [focused business workflow](../../.github/workflows/ci-business-kind.yml)
reuses the Gradle test owner and the existing `RiskCommandKubernetesTest` without
introducing another business assertion, Shell scenario, CI reporter or test
framework. Its selected scope is Risk admission → durable PostgreSQL outbox →
Kafka Connect publication and byte-exact Kafka record. This is independent
of the existing Java, Native, Flyway and CDC jobs, and narrower than the
`resting-buy` complete-local test.

The job uses a GitHub-hosted Ubuntu runner and `kubernetesMode=disposable`.
It installs checksum-verified kind and kubectl binaries, uses Native Docker
`default`, constructs run-attempt-specific image tags and evidence paths,
and leaves `kubernetesRetain=false`. It binds image content to current
source and Docker identities through existing Gradle and Jupiter owners.
The job has an 80-minute hard budget and a 65-minute Gradle command budget,
with up to four further minutes for graceful diagnostic and cleanup handling.
On failure, it invokes the existing `cleanupKubernetesRun` only when a
run-owned `ownership.json` exists. That separate cleanup has a five-minute
budget and validates recorded daemon and Kubernetes identities. It does not
change the original business verdict. Forced runner termination can still
prevent the step from running. This bounds the worst-case Gradle attempt
and cleanup to 74 minutes 45 seconds, reserving the rest of the job budget
for setup, evidence collection and artifact upload.
It requires the existing Kubernetes `status`, `assertions`, `diagnostics`,
and `cleanup` summary fields to all equal `PASS`. A missing or failed
summary cannot pass the step. Jupiter reports remain in the CI job workspace;
the uploaded artifact is limited to the source/image preflight, final summary,
and selected redacted diagnostic files. Raw JUnit XML/HTML and business
payloads are not uploaded because test output can contain sensitive details.

The workflow uses identical push and PR path filters for services, shared
contracts, Gradle, native/Matching build inputs, test owners, container
builds, Kubernetes manifests, configuration and necessary Shell operators.
A documentation-only commit does not trigger the expensive job. Concurrency
does not cancel a running cluster. GitHub keeps at most one pending run for
the same ref and can replace an older pending run with a newer request.
The existing run-owned verifier diagnoses before cleanup and refuses to
adopt an old disposable cluster. GitHub cancellation and hard process-kill
cleanup still require the explicit P3 failure-path audit.

## P3: host observations and recovery boundary

The workflow collects 15-second host-level samples: CPU busy/idle jiffies,
available memory, available Docker/workspace disk, and network byte counters.
It also records Docker storage totals and the build-input stage on failure.
These are infrastructure observations, not another business test owner.
Host network deltas include other traffic and do not measure exact image
archive transfer. The image importer removes each temporary archive after
import. The per-worker `image-import.log` now records the actual archive
file size (`archive_bytes`) and each `kind load` duration (`import_elapsed_ms`).
The sum of archive sizes over successful node imports measures the payload
offered to `kind load`, not network wire bytes or unique compressed layers.
Do not infer archive bytes from Docker image logical sizes.

When the business step fails after recording run ownership, the existing
`cleanupKubernetesRun` diagnoses and attempts exact owned cleanup. Its
cleanup-only report is separate from the original business result. When
no ownership receipt exists, no cleanup authority is available. The runner
checks for leftover disposable kind containers without deleting them and
fails closed if it finds any. Neither case establishes cancellation cleanup
under forced runner termination;
that requires a separate failure-path check.
GitHub's [workflow cancellation contract](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-cancellation)
can interrupt the active step and forcibly terminate a cancelled job after
five minutes. An `always()` cleanup condition allows recovery to execute, but
does not prove it finished. AC-06 requires an isolated, recoverable hosted
cancellation **after** an ownership receipt, followed by a verified
source-run/cluster/namespace cleanup target and absence of its exact owned
resources. A normal failed Job with successful recovery is separate evidence.

The first hosted execution of `be9820e2` failed before Kubernetes began:
[run 42844556998, attempt 1](https://github.com/WenHsuanYu/SimpleMatch/actions/runs/42844556998).
`buildKubernetesBarrierImage` received HTTP 404 for
`perl-base_5.40.1-7ubuntu0.4_amd64.deb` from Ubuntu APT. On 2026-11-10
a fresh local `ubuntu:26.04` container successfully downloaded the
same package after `apt-get update`. Repository propagation is plausible
but not proven. A second hosted attempt was requested on unchanged source.
Preserve each attempt's actual outcome and evidence independently.

The final P3 SHA `f263ce42` failed in
[hosted run 42851141586](https://github.com/WenHsuanYu/SimpleMatch/actions/runs/42851141586)
after all images were built. Its disposable Kubernetes run
`0d7fe55d-ca4a-43b5-b273-4438253e6316` recorded
`status=FAIL`, `assertions=FAIL`, `diagnostics=PASS` and
`cleanup=PASS`. The `kafka-topic-provisioning` Job had one Pod
terminate with exit code 1 and reached `BackoffLimitExceeded`.
Kafka's three broker Pods remained Running, and the Account and Risk
Flyway Jobs succeeded. The independent ownership-bound cleanup also
returned PASS with the original target already absent. These facts do
not establish the topic Job's internal error: the original artifact
allowlist omitted its separately redacted Job log and Job/Pod records.
The next workflow revision includes only those specific Job diagnostics,
without changing the business assertions or deployment inputs.
The original 45-minute Gradle limit left insufficient room for the
business checks after the 43-minute failed setup. The workflow raises
the finite Gradle limit to 65 minutes and the job limit to 80 minutes,
leaving 5 minutes 15 seconds for setup and artifact collection after
the maximum four-minute termination grace and five-minute
45-second ownership recovery allowance.

The failed hosted execution provides a measured **host-level** envelope:
175 samples over 43.57 minutes, mean interval CPU busy fraction 44.7%
(maximum 78.7%), minimum `MemAvailable` 10.88 GiB, and minimum
Docker/workspace available space 35.53 GiB. These metrics do not prove
per-container CPU, memory or image-transfer usage. Host network interface
counts can decrease as kind interfaces disappear, so host network
counter differences are not a reliable image-transfer measurement.

The next hosted selection on workflow SHA `68fbdc00`,
[run 42861322462](https://github.com/WenHsuanYu/SimpleMatch/actions/runs/42861322462),
also finished with `status=FAIL`, `assertions=FAIL`,
`diagnostics=PASS`, and `cleanup=PASS`. Kafka topic provisioning,
the Matching barrier fixture, and both Flyway Jobs succeeded.
The verifier then rejected an imported workload image index with
`workload index must identify one linux/amd64 manifest`.
`KubernetesWorkloadImages` required one direct platform-labelled
descriptor. The imported image IDs pointed to content-addressed OCI
indexes. The artifact did not retain the index contents, so the exact
descriptor arrangement remains unknown. The source correction checks
nested indexes, supported OCI/Docker media types, declared platforms,
content hashes, and image config identities. It rejects ambiguous or
wrong-platform runnable manifests.
The three Error Prone warnings in the selected Kubernetes tests are
also addressed without suppressions.
Separate read-only inspection of a **local** kind image showed one
linux/amd64 manifest and one platform-less containerd attachment.
The attachment used `io.containerd.manifest.subject` to name the
runnable manifest. Its config reported `unknown/unknown`. This
observation does not identify the missing hosted index bytes. A
focused regression now requires that such an attachment have a valid
subject and a non-runnable config before the verifier ignores it.
The same validation now applies to Docker attestation descriptors.
An attestation label alone cannot exclude a second runnable manifest.
The verifier requires a verified runnable subject and an
`unknown/unknown` config before excluding the attached content.

The first fresh `verifyAll` invocation on source `49edc6c2` failed before
Kubernetes deployment. Gradle selected `RestingBuyKubernetesTest`, but its
JVM received no `kubernetesScenario` property. The JVM defaulted to
`risk-command`, and `KubernetesBusinessEnvironment.preflight` rejected
the profile mismatch. The Gradle run properties now include the selected
scenario for both the build-input verifier and Jupiter. The failed
invocation does not establish a complete-local PASS. A fresh source-bound
`verifyAll` result is required after this correction.
Two later complete-local attempts on source `8f6224aa` failed in the
Flyway runner Docker image build, before Kubernetes deployment. Its Gradle
Wrapper could not resolve `services.gradle.org`. A minimal Docker build
reproduced the DNS failure under default BuildKit networking, while the
same lookup succeeded in ordinary `docker run` and in a Docker build with
`--network=host`. The workstation can select this proven local workaround
with `-PkubernetesBuildNetwork=host`. Hosted CI retains default build
networking; the opt-in does not alter business assertions or eliminate the
need for a new full-local four-field PASS.

The matching core now calculates the taker weighted average with standard
C++20 64-bit arithmetic instead of the compiler-specific `__int128` type.
The replacement retains the exact integer quotient and remainder across
partial fills. Native CTest and strict pedantic compilation verify this
change. It changes executable verification inputs, so earlier full-local
P6 evidence remains historical and cannot certify the updated source.

## P4: historical source-aligned acceptance pre-audit

The file-level diff from complete-local source `1a3fa704` through the
P3 workflow-only changes contains only `.github/workflows/*.yml` and
Markdown files. No service, native, Gradle, Kubernetes test, deployment
manifest or acceptance evaluator input changed. These paths are excluded
from both `KubernetesSource` fingerprints. The original S5 P6
`verifyAll` PASS and its source/run/image identities therefore remain
eligible for conditional reuse under the #203 owner amendment; do not
relabel the historical report as a new run.
The later OCI index verifier correction changes executable source and
test definitions. The original P6 PASS remains historical evidence,
but cannot certify the new source. Require a fresh source-aligned
`verifyAll` PASS before declaring the final complete-local result.

The tracked S6 inventory remains 34 Shell files / 6,896 physical lines,
with zero tracked Python and Ruby files. This P3 change adds host
observations and exact-owner recovery calls to the existing workflow.
It does not create or translate business tests, or add a Shell file.
The requirement dispositions and intentional retirements in P0 and
S5 remain authoritative. Final AC-10 requires a successful selected
hosted business run on the final workflow revision, grounded resource
evidence and a clear image-transfer limitation. AC-12 and the final
issue handoff require a final review; an incomplete retained check
cannot be recorded as an intentional retirement or PASS.

The selected hosted workflow has now passed on source `3a61065e` in
[run 42956991465](https://github.com/WenHsuanYu/SimpleMatch/actions/runs/42956991465).
The downloaded `risk-command-42956991465-1` artifact contains the original
`summary.json` verdict with `status=PASS`, `assertions=PASS`,
`diagnostics=PASS`, and `cleanup=PASS`. Its stage evidence binds the full
source revision and source/verification fingerprints. In 175 host samples
covering 43 minutes 33 seconds, mean interval CPU busy was 40.24%, maximum
98.73%, minimum `MemAvailable` was 7.289 GiB, and minimum Docker/workspace
available space was 35.54 GiB. There were 27 image imports (nine distinct
images on three workers); the sum of node-specific archive sizes was
15.408 GiB and the longest single import took 29.004 seconds. Archive
sizes are **not** network transfer bytes; host metrics are **not** per-container
peaks. This source-bound hosted result is historical after any further
executable verifier change and must be rerun on the eventual final HEAD.

## P0 handoff against AC-01 through AC-12

| Acceptance | Current evidence and remaining gate |
| --- | --- |
| AC-01 | PASS: S0 baseline dispositions, S5 final retirement groups, the R01-R26 owner tables above and final S6 audit preserve every retained requirement's authoritative owner and document intentional verification losses. The S6 changes did not add business test owners or obsolete callers. |
| AC-02 | PASS: recounted on `076cb0e7`, baseline 170 tracked Shell files / 34,923 physical lines to 34 files / 6,896 physical lines, with zero tracked Python/Ruby. File reduction is 80.00%; physical-line reduction is 80.25%. |
| AC-03 | PASS: the S5 retirement audit records duplicate removal and no concealed language relocation. Final S6 changes add no Shell/Python/Ruby scripts, business assertions in workflow YAML, duplicate test framework, or permanent compatibility reporter. |
| AC-04 | CONDITIONAL: source `076cb0e7` passed full unfiltered `verifyAll` with `resting-buy`, Native 85/85, R19 420/420 and source-bound images. The subsequent Kafka provisioning manifest correction changes deployment inputs and requires a new complete-local execution. |
| AC-05 | PASS: `verifyAllScope` blocks excluded tasks, filtered/dirty source and altered complete-local profiles. Live verification uses fresh run and image identities. |
| AC-06 | BLOCKED: `076cb0e7` dedicated full-local run preserves `diagnostics=PASS`, `cleanup=PASS` and the canonical cluster. Hosted failure attempt 1 preserves FAIL/FAIL/PASS/PASS and an independently recorded owned cleanup PASS. Recoverable hosted cancellation after an owner receipt still lacks current-workflow execution evidence. |
| AC-07 | PASS: existing JUnit/Gradle and `VerificationRun` report owners require named, non-skipped tests and four-field Kubernetes PASS. The separate cancellation evidence gap is tracked under AC-06. |
| AC-08 | PASS: Spring-managed Jupiter, existing native tests and dependency Testcontainers owners remain authoritative. No new framework is required. |
| AC-09 | PASS: existing pinned manifest owner is `manifestCheck`, selected in Java and CDC CI as well as `verifyAll`. |
| AC-10 | BLOCKED: historical hosted PASS on `3a61065e` is not final-source evidence. Hosted `42973661161` attempts 1 and 2 on `076cb0e7` both failed the additional Kafka configuration Admin query before business assertions, with original FAIL/FAIL/PASS/PASS and separately owned cleanup PASS. The corrected source requires a fresh selected Hosted four-field PASS with host and image-import metrics. Complete hosted CI is optional. |
| AC-11 | BLOCKED for the changed deployment input: `076cb0e7` previously passed full-local `verifyAll` (37m15s, 194 Gradle tasks, Native 85/85), four-field PASS, R19 420/420 and exact cleanup. This original report remains valid only for its source. The Kafka provisioning manifest correction requires a fresh full-local run before acceptance. |
| AC-12 | BLOCKED: current plan, specification, and historical certification links were reconciled with retired Shell owners. A local-link audit of `docs/plans` and `docs/verification`, excluding the frozen S0 inventory, found no missing file targets. Final hosted evidence, cancellation cleanup and Issue status still require reconciliation; no Issue may close before those gates pass. |

For historical comparison only, original S5 P6 evidence is run
`6364220d-c45a-43e2-9100-daf098e78046` at
`out/verification/kubernetes/7458c622-11ad-4cdd-8f83-69bc519582ef/`.
Its `summary.json` records `status`, `assertions`, `diagnostics` and `cleanup`
as `PASS`. The runtime fingerprint is
`025ade2427eaa7bed00bc499a46bb9515662c9d7b742744947cc74a8c659c954`.
The verification fingerprint is
`24efd110a5c83c0438c32740e180d29faaf889f284759328803d5cc901fabbd7`.
At the earlier P0/P1 review, only Markdown and GitHub Actions workflow
definitions had changed, so conditional reuse was considered. Subsequent
executable test and verifier changes made that reuse inapplicable. The
`076cb0e7` complete-local run below remains valid for its original source.
The later Kafka provisioning manifest correction requires a new complete-local
result. No historical report is relabeled or upgraded.

**S6 state (2026-11-11):** P0/P1 owner and workflow dispositions, focused
business CI, and measured hosted resource/import evidence are available.
Failure-path owned cleanup is supported by real hosted failure artifacts.
Recoverable hosted cancellation after an ownership receipt is still not
proven by an actual current-workflow run; local interruption tests alone
do not establish that AC-06 requirement. Do not cancel a production verification
run to manufacture this evidence. The `076cb0e7` complete-local PASS is
recorded below, but both selected Hosted attempts on that SHA failed.
The Kafka provisioning correction requires new source-aligned local and Hosted
four-field evidence. Keep #203 and #196 OPEN
until the remaining gates and AC-12 source/evidence reconciliation are resolved.

Three independent full-local runs on `3a61065e`, all with
`-PkubernetesBuildNetwork=host` and the unmodified `resting-buy` acceptance
profile, have terminal **FAIL** results, each with `diagnostics=PASS` and
`cleanup=PASS`:

| Run | Evidence directory under `out/verification/kubernetes/` | Actual failure |
| --- | --- | --- |
| `b34e0031-bb75-4291-b1f9-09ba52c797cf` | same name | Kafka StatefulSet never Ready; 41m46s; Kafka quorum could not communicate across stale kindnet PodCIDR routes. |
| `5562aa7f-2297-45ee-9909-8581ea9164d9` | same name | Kafka StatefulSet never Ready; 22m39s; correcting two stale CNI IPAM subnets exposed a second missing-route defect in old host-network kindnet Pod identity. |
| `ebdce8af-f407-4ae6-9b0b-2fd28784e569` | same name | Full Kafka 3/3, Matching 15/15 and the real `resting-buy` lifecycle progressed into R19 V2; the exact old Query Pod-route detachment check failed; 35m15s. |

The owned namespaces from all three failures were removed by their respective
verification runs. Canonical `simplematch-live` (one control plane, three
workers), its local registry, and non-owned persistent resources were retained.
Two workers had stale CNI IPAM subnets. After backing up their original
configuration under ignored `out/ci-investigation/`, the subnets were aligned
to the live Node PodCIDRs. Two missing cross-node routes were then restored
in place and verified through kindnet reconciliation cycles. These local
environment actions are not repository code changes; they do not promise
persistence through future Docker node-IP reassignment.

R19 V2 captured real stopped Query Pod UIDs, container tasks and process
identities before failing in `KubernetesQueryTransport.routeDetached`.
The prior predicate required an `eth0` fallback to carry a gateway field,
which is not guaranteed by Linux when the old Pod IP is no longer a directly
attached veth. The strict verifier correction now captures the old Pod's exact
node-local `/32` route before the stop and requires that exact route and the
original veth to be absent afterward, with only an `eth0` fallback accepted.
The independent CRI/PID stop fence, blocked old/new sockets and prohibition
on treating timeouts as revocation remain mandatory. The RED regression
reproduced the false-failure predicate; `:tools:kubernetes-tests:test` and
`staticAnalysis` then passed after the correction. These focused results did
**not** upgrade the third failed full-local run. The subsequent clean-source
complete-local PASS is documented below; final-source hosted CI remains a
separate acceptance requirement.

## Source-aligned S6 complete-local verification (2026-11-11)

Commit [`076cb0e7`](https://github.com/WenHsuanYu/SimpleMatch/commit/076cb0e78bd7e5929ce09945087a0381a1499a84)
includes the exact `/32` Query Pod route proof and its RED/GREEN tests, without
weakening CRI stop, HTTP failure or ownership assertions. The unmodified full
command `PATH=/home/alexyu/go/bin:$PATH VCPKG_ROOT=/home/alexyu/vcpkg
./gradlew --no-daemon verifyAll --console=plain --max-workers=2
-PkubernetesBuildNetwork=host` completed **BUILD SUCCESSFUL in 37m15s**, with
194 Gradle tasks and native 85/85 CTest. Its run-owned Kubernetes identity
`b6ca4c11-db56-414b-8721-65296c006c38` reports all four independent fields
`status=PASS`, `assertions=PASS`, `diagnostics=PASS`, `cleanup=PASS` at
`out/verification/kubernetes/f7b96765-5b9e-41cf-9928-d841f9a5a81a/summary.json`.
The same directory's `preflight.json` records source fingerprint
`38bc9a57cad46b01a34a68a976e6e6980bbb12583fd2b4f81f8c18187818f852`
and verification fingerprint
`1d6ff5db34500d8c03b7fdb373aa42dace0839eed2f7ceb584743262d1d52075`.
R19 principal matrices each report `complete=true` and `verifiedCells=140`
for V1/V2/V3, totaling 420 cells. V2/V3 transport evidence records each old
Query veth and its `/32` route absent; both gateway-bearing and on-link
`eth0` fallbacks were observed in genuine Kubernetes runs. The exact run-owned
namespace was removed. All four original `simplematch-live` nodes remained
Ready and `simplematch-local-registry` was retained. Only the four original
private untracked paths remained after verification.

The same executable SHA's focused hosted
[run `42973661161`](https://github.com/WenHsuanYu/SimpleMatch/actions/runs/42973661161)
**attempt 1** produced the independent original result
`status=FAIL`, `assertions=FAIL`, `diagnostics=PASS`, `cleanup=PASS` in
downloaded artifact `risk-command-42973661161-1`. Kafka brokers were Ready,
but `kafka-topic-provisioning` created `matching.commands` and
`matching.events`, then failed to describe configuration for `matching.events`
within the already-bounded three-minute retry window. The Job reported
`BackoffLimitExceeded`, exit code 1. The separately invoked owned cleanup
returned four-field PASS. Host samples cover 40m05s (161 samples), mean
interval CPU busy 47.33% (peak 82.03%), minimum `MemAvailable` 10.84 GiB,
and minimum Docker/workspace available 35.54 GiB. Its cause is not proven by
these observations; do not label it a resource exhaustion or application
assertion failure. The image log records 27 imports (nine distinct images to
three workers), 15.408 GiB summed per-node archive payload (5.136 GiB of
unique-image archive sizes), and a 27.350-second longest individual import;
neither payload total is a measured network-transfer byte count. The same-SHA
rerun was actually executed as **attempt 2**, preserving attempt 1 evidence.
Its independent original artifact `risk-command-42973661161-2` also records
`status=FAIL`, `assertions=FAIL`, `diagnostics=PASS`, and `cleanup=PASS`.
The provisioning Job created `matching.commands` but failed the additional
configuration description for that first topic. Its Pod terminated with exit
code 1 and `BackoffLimitExceeded`. Independently invoked owned cleanup again
recorded four-field PASS. Attempt 2 has 165 host samples over 41m03s, with
mean CPU busy 47.00% (peak 81.76%), minimum host `MemAvailable` 9.304 GiB
and minimum Docker/workspace free disk 35.541 GiB. Nine images imported 27
times across three workers (15.408 GiB summed archive sizes); the longest
single import took 25.086 seconds. These metrics are host aggregates and
archive payload sizes, not per-container peaks or network-transfer bytes.
Both attempts used the same source and verification fingerprints above.
Neither original failure may be relabelled or upgraded.

## Kafka provisioning correction after two failed hosted attempts

The original provisioning Job launched `kafka-configs.sh --describe --all`
after a successful `kafka-topics.sh --describe` for each topic. That extra
Admin CLI did not succeed before the original shared 180-second deadline
in either hosted attempt. The precise error was not emitted by the script.
The artifacts do not prove CPU throttling, broker outage, or an application
assertion failure.

An isolated container running the pinned `apache/kafka:4.3.1` image confirmed
that a topic description already contains partition count, replication factor
and the explicit topic-level configuration overrides. The ignored regression
fixture at `out/ci-investigation/s6-kafka-cli-regression/check.py` produced
RED on the original script when this extra CLI returned failure, even with
a valid topic description. The corrected script produced GREEN without a
second Java Admin command. Additional negative fixtures reject wrong or
prefix-matching partition counts, replication factors, cleanup policies,
retention values, minimum ISR, and missing configuration overrides.

The correction reads one exact topic header and parses explicit config
tokens. It rejects missing, duplicate, malformed or mismatched required
configurations. The original 15-second Kafka operation limit, shared
180-second retry deadline and RF/partition/configuration contracts are
unchanged. This is a new deployment input and does not inherit the prior
complete-local PASS. New current-source local and Hosted evidence remain
required before AC-10/AC-11 can pass.

The final documentation audit also corrected references to retired bootstrap,
phase-graph and Market Data verdict scripts. The historical certification
README link now targets its existing acceptance-contract section. A repository
link-target scan found no missing local file links in the current planning and
verification Markdown documents. The frozen S0 inventory intentionally retains
links to scripts deleted by the migration; those links are historical records,
not executable interfaces or current implementation guidance.

# S5 requirement disposition and integration

Owner: [#202](https://github.com/WenHsuanYu/SimpleMatch/issues/202).
Authority: the 2026-11-02 owner amendment in [#196](https://github.com/WenHsuanYu/SimpleMatch/issues/196).
Starting source: `01926d56a51370f27cecfded276e56f28fb777f8`.
Starting Shell inventory: 148 tracked files and 29,512 physical lines.

## Acceptance and scope

Review each responsibility before selecting its implementation.
Retain core behavior and basic negative cases at the existing public test seam.
Retire obsolete checks with their dedicated fixtures, callers, and phases.
Do not create Java copies of source matchers or completed migration checks.

S5 remains incomplete until the following requirements have evidence:

- Every retained requirement has an authoritative owner and executes through one local Gradle entry point.
- The entry includes native and Flyway verification and prepares its required deployed resources automatically.
- Dedicated and disposable cluster modes preserve ownership, diagnostics, restoration, and exact cleanup.
- Missing, skipped, stale, or omitted retained results cannot produce complete PASS.
- Partial and focused results retain their limited claims and current source, run, and image identities.
- Superseded orchestration and dedicated compatibility support are removed with their delivered replacement.
- Tracked Shell totals meet both minimum gates: 51 files and 10,476 physical lines.
- A fresh complete run validates the final integrated executable source.

The historical [S0 ledger](shell-test-reduction-s0/requirements.md) provides requirement IDs and navigation.
The dispositions below supersede only the named responsibilities.
Other S0 requirements remain unresolved for S5 until reviewed and recorded here.
An unresolved requirement blocks its deletion and complete acceptance.

## Remaining review rule

The owner reiterated: do not over-engineer or over-verify.
The S0 list does not automatically make every historical scenario or assertion mandatory.
Use existing service, dependency, native, and Kubernetes owners for current core behavior.
Review each remaining legacy responsibility for an independent current purpose before retaining it.
Record a coherent retirement and its coverage loss before deleting its dedicated support.
Do not build a second verification framework or copy historical Shell assertions into Java.

## Accepted execution order

The owner accepted two sequence refinements in [#202](https://github.com/WenHsuanYu/SimpleMatch/issues/202):
P0 requirement decisions → P1/P2 Native and H2 work → P5a minimal Gradle entry → P3/P4 retained gaps → P5b final integration/retirement → P6 acceptance.
P5a connects existing test owners early; it cannot report complete PASS while retained coverage is unresolved or unexecuted.

[#203](https://github.com/WenHsuanYu/SimpleMatch/issues/203) owns focused hosted CI and final program acceptance.
It may use the fresh #202 complete-local evidence when only CI wiring, documentation, or metadata changes.
Runtime/build inputs, test definitions, complete-local scenario selection, and acceptance semantics must remain unchanged.
Preserve the evidence's original source/run/image identities and prove that later differences are outside those inputs.
Relevant input or semantic changes require a fresh complete-local run. Selected hosted CI still executes on the final head.

## Completed phase and source checks

This disposition precedes deletion and execution of the remaining verification.
It concerns R03 and R26, not retirement of transaction or reservation behavior.

| Responsibility and former implementation | Disposition and remaining owner | Intentional verification loss |
| --- | --- | --- |
| Completed Phase 5 source-pattern gate, `scripts/archive/check-phase-5-gate.sh` | Retire the archived file and builder-name/source-pattern checks. The transaction policy and offline Market Reference architecture remain authoritative. | No automatic broad HTTP-class matcher. That matcher also rejected permitted internal service observation. Source review owns the prohibition on exchange calls in the trading path. |
| Completed Phase 6/7 checklist and artifact gate, `scripts/archive/check-phase-6-7-gate.sh` | Retire completed checkbox and exact-file-location checks. Existing Account/Risk behavior tests remain the verification owners. | No automated assertion about completed plan checkboxes or historical class/migration paths. |
| Transaction document structure, `scripts/check-transaction-acceptance-criteria.sh`, its dedicated test, and `ci-transaction-criteria.yml` | Retire the Markdown-heading framework and its CI job. Preserve the transaction policy, historical plan, and behavioral review requirements. | No automatic heading, policy-link, or required-field matcher. This checker never proved transaction behavior. |
| Completed Account v1/v2 transition, `scripts/check-account-reservation-v2-cutover.sh` | Retire source-location, deleted-type, and configuration-string checks. Reuse the existing Account v2 gRPC and Risk saga tests. | No standalone matcher for reintroduced v1 source, exact stub construction, server registration, or TLS text. Compilation, service tests, manifest checks, and review retain their own scopes. |

Existing test owners include:

- `AccountReservationApplicationServiceTransactionTest`: reservation/outbox atomicity and rollback.
- `OrderAdmissionApplicationServiceTransactionTest`: durable admission/outbox state, replay, and transaction boundaries.
- `AccountReservationV2GrpcServiceTest`: typed Account RPC behavior.
- `AccountReservationSagaRecoveryIntegrationTest`: actual Account v2 RPC, separate local transactions, and pending recovery without duplicate reservation.
- `GrpcAccountReservationClientAccountIdentityTest`: Account identity across the typed client seam.

At the S5 starting revision, these transaction suites used disposable H2 databases.
Those historical results do not establish PostgreSQL or deployed Kubernetes behavior.
The owner now requires removal of H2, with a separate mock-or-PostgreSQL decision for every case.
The existing `verifyPostgres` and `verifyKubernetes` owners retain those distinct scopes.
No service implementation, immutable analysis policy, historical report, or runtime deployment changes in this retirement group.

Current documentation removes the retired commands.
The frozen S0 inventory and baseline remain historical records of the old paths.

## Native verification owner

R22 retains the native CMake/CTest suite and its actual command processing, replay, and integrity checks.
Gradle `nativeTest` configures, builds, and executes those existing tests directly.
The public test seam is the Gradle task and its fresh CTest JUnit report.
The initial acceptance invocation failed because that task did not exist.
Acceptance requires an actual native suite run, positive discovered tests, and no skipped or disabled results.
Normal CMake/vcpkg build caches remain permitted. CTest executes again on every invocation.

Retire `scripts/run-matching-local-depth-certification.sh` without translating its ten-cycle loop or aggregate report.
The existing `MatchingPartitionRuntimeDriver` local-day test owns bounded 10,000-command replay.
The existing `simplematch_matching_capacity_benchmark` CTest owns command/event integrity and deterministic checksums.
The intentional loss is the independent repeated 102,000-command calibration and its report schema.
Repeated calibration adds no separate basic functional requirement under the amended scope.
Historical ten-cycle reports remain unchanged. This retirement makes no new soak, throughput, or deployed recovery claim.
The separately consumed capacity launcher and deployed Matching scenarios remain pending their own cutover.

## Remaining static snapshots and unused aliases

R01 retains the shared `manifestCheck` owner from S4.
Retire `scripts/validate-local-resilience-contract.sh` and its inline Ruby snapshot assertions.
Its current callers are documentation procedures, not the deployed recovery runners.
The intentional loss is independent matching of exact replica counts, placement, PDB values, tolerations, probe timing, and logging source patterns.
Retirement also removes independent checks for declared resource requests/limits, Flyway deadlines and PostgreSQL wait initialization, and application metrics exposure.
Canonical kind/StorageClass file-presence checks and the topic-provisioning Job presence check also retire.
Schema validation and basic probe policy remain. They do not replace those exact assertions or prove runtime recovery.
Runtime placement, dependency recovery, and serving requirements remain pending their own S5 dispositions and execution.

Remove three compatibility aliases without adding replacements:

- `scripts/lib/critical-consumer-certification-matching.sh` only sources the canonical `matching-status.sh` helper. Current runners source that canonical helper directly.
- `scripts/test-critical-consumer-certification-matching.sh` only invokes the canonical `matching-status-contract.sh` test.
- `scripts/test-critical-consumer-kubernetes-config.sh` only invokes the canonical `deployment-contract.sh` test.

The canonical helpers and tests remain available for their current consumers.
Alias deletion removes alternate invocation paths and no independent assertion.
The frozen S0 inventory remains unchanged.

## Native dependency preparation blocker

The first `full-native-dev` invocation exceeded its 15-minute configure deadline while vcpkg built gRPC.
This was a failed invocation, not a native-test PASS. No child compiler remained after termination.
Its dependency plan selected future RPC, PostgreSQL, Redis, and observability libraries.
Current CMake targets do not consume those capabilities.

Keep `full-native-dev` on `tests`, `messaging`, and `json-config` plus the manifest's core dependencies.
Retain `BUILD_TESTING=ON`, `SIMPLEMATCH_ENABLE_MESSAGING=ON`, and all existing native targets and test registrations.
The optional capability definitions and dependency versions remain unchanged.
This removes unused dependency preparation from the actual verification path, not a native test or product capability.
Validate the corrected preset through the same Gradle/CMake/CTest interface with a new evidence directory.

## PostgreSQL smoke scope

R02 retains actual service-owned migrations and validation against disposable PostgreSQL.
The smoke exercises actual Gradle task wiring, which direct Flyway API tests do not cover.
`PostgresIntegrationTest` remains the small owner for one migrate/validate invocation and evidence that each configured owner has applied migrations.
Existing SQL constraint probes remain during the H2 cutover.
After service suites use real PostgreSQL, retire duplicate probes whose current behavior those suites own.
Existing Query HTTP/JDBC tests retain their own real PostgreSQL business behavior.

Retire the local smoke's repeated Flyway run, empty-history checks, default-encoding assertion, table-name catalog, forced query-plan checks, and Gateway collector snapshot check.
These checks are not all necessary to establish that current service migrations work on PostgreSQL.
The intentional loss is independent local verification of those catalog, plan, repeat, and collector-report details.
External plan/schema operators keep their existing explicit scope and current consumers.
The retired Gateway snapshot helper loses its dedicated padding fixture, JSON baseline, Gradle inputs, and Shell alias.
Keep the Gateway runtime SQL collector while it still has live scenario consumers.
No new Java verification layer or assertion matrix replaces the retired checks.

## P0 findings for the first shared entry

The ordinary service test task is not a complete list of retained owners.
Gateway deliberately separates its in-process FIX/JDBC certification from `test`.
The complete entry must select `certificationTest` explicitly; opt-in external FIX endpoints remain operator scope.
Native complete-local selection must use `full-native-dev` to include messaging, while focused presets keep their narrower scope.

Query currently runs the same JDBC/HTTP classes in both `test` and `postgresJdbcTest`.
Merge execution ownership before adding the aggregate: keep those classes in `test`, make `postgresJdbcTest` depend on it, and select only the additional collector class in the latter task.
Keep Redis TLS/ACL verification with `redisSecurityTest` through `verifyDependencies`.
Acceptance: the Gradle graph schedules the ordinary Query suite once and the additional collector once, with no deleted business assertion or required exclusion.
The ordinary suite retains its existing exclusions; it must not acquire a Python/psql collector prerequisite.
Validate wiring without Docker now; complete Query execution remains required after Docker access is restored.

Existing dependency owners retain PostgreSQL migrate/validate and RF1/RF3 exact CDC observations, connector/broker recovery, and crash redelivery.
They do not establish Kubernetes worker recovery or the public FIX-to-downstream chain.
`verifyKubernetes` currently owns the bounded Risk admission/outbox/Kafka scenario, with its existing build/lifecycle/cleanup support.
R11–R21 and the remaining deployed R22 claims still require explicit P3/P4 dispositions before their old runners can be removed.
No deletion of those unresolved deployed responsibilities is approved merely by the component results above.

The Query task-wiring dry-run completed successfully with `test` followed by `postgresJdbcTest`, each scheduled once.
Evidence: `/tmp/simplematch-202-query-task-wiring.log`.
Dry-run `SKIPPED` labels mean no test executed; this is task-graph evidence only and does not satisfy runtime acceptance.

## Trading-day source matcher retirement

Retire `scripts/test-local-certification-trading-day.sh` and its Local Resource Lifecycle CI call.
This script matches exact Shell assignments, function names, and adjacent source lines, then checks GNU `date` against a fixed timezone example.
It does not invoke the actual live-input guard or submit an order.
Those source-layout and operating-system checks do not merit migration under the owner's test-value rule.
The intentional loss is automatic enforcement of the exact Taipei default expression and guard-to-release source adjacency.
Keep the runtime trading-day guards and their current callers unchanged. This retirement is not evidence that stale-day rejection or deployed order handling passed.
Acceptance: remove this checker and its active CI caller, preserve historical inventory entries, and introduce no replacement source matcher or date-library test.

Keep the current workload adapter's unresolved-image negative check until its runtime caller moves.
Keep the old skip-semantics test while the aggregate Shell engine remains active: it exercises occupied-evidence rejection, failure propagation, fresh execution, and PARTIAL results.
Its historical phase IDs/report layout need no one-for-one replacement. P5 must retain the underlying safety rules through existing framework owners.
After the trading-day source matcher retirement, the worktree contains 136 tracked Shell paths still present and 28,815 physical lines.
Both reduction gates remain unmet. No runtime verification result follows from this retirement.

## Review cadence

The owner requires two-axis review at a completed sequence milestone, such as P3, rather than after every small slice.
Use focused checks during implementation. At the milestone, review standards and specification, fix concrete findings, and verify the affected behavior.
Do not repeat two-axis review for routine intermediate edits.

## Critical-consumer invocation alias retirement

Retire `scripts/run-critical-consumer-failure-certification.sh`, which only forwards arguments to the canonical runner.
Remove its dedicated existence/executable/help assertions from `deployment-contract.sh` and update current runbook commands.
The canonical failure runner and all actual failure/recovery assertions remain.
The intentional loss is the historical alternate command path; it has no independent scenario or verdict.
Acceptance: current commands use `scripts/end-to-end/critical-consumers/run-failure-certification.sh`, and no active consumer requires the alias.

The retained deployment contract passed after alias removal; no deployment was executed.
Current Shell worktree totals are 135 files and 28,806 physical lines, still above both minimum gates.

## P0 critical business requirement decisions

Integrate Gateway and Matching recovery after the new public FIX baseline instead of adding another baseline deployment.
Retain actual reconnect/resend, stable claim identity and singular durable effects; retire the Gateway runner's independent WAL/journal byte-prefix matrix, continuous owner sampling and duplicate report fixtures.
Existing WAL/session tests own file integrity; this cutover makes no new concurrent-owner or node-failure claim.
Retire the callback-counter unit case in `FixRecoveryProbeTest`; actual FIX reconnect/resend owns recovery. Retain its body-digest case, which distinguishes altered order terms from advancing session headers.
Retire `FixCancelProbeTest`'s positive field-copy case in favor of the real post-recovery cancel; retain its Pending Cancel and unrelated-order rejection case. Reuse `MatchingEventObservationMain` and `MatchingEventRedeliveryMain` without translating their Kafka algorithms.
For R11, use one actual PostgreSQL outage with critical consumers running, plus a held routed Matching process. Retire simultaneous removal of all consumer Pods; the new scenario makes no combined Account/Persistence process-restart claim. R12 verifies automatic pause of new orders and actual FIX rejection, rather than treating stale input as terminal day closure.
Retain terminal `close-day` as a distinct core workflow: the Gateway must initiate Risk-owned Close Barriers, Matching must expire the resting order, and downstream consumers must release the reservation and persist the terminal state. Run this last in the same deployed scenario. Automatic ingress pause does not prove terminal closure. Reuse the existing Kafka Close Barrier observer; do not port its Shell predicate matrix or add another deployment.
The terminal scenario checks the selected expiration in all three consumer inboxes and the corresponding business state. Retire the separate all-partition consumer high-watermark report and predicate fixtures; this scenario makes no broader backlog-drain claim.

| Requirement | Decision and retained value | Current owner and cutover condition |
| --- | --- | --- |
| R13 public resting order | Keep the real FIX → Risk/outbox → Kafka/Matching → Persistence/Account correlation. Component tests cannot detect cross-service identity or delivery wiring defects. Merge the standalone normal run into the pre-fault baseline of the retained recovery scenarios in complete-local selection; do not add a third identical baseline-only deployment. | Deployed Jupiter verified this chain in the canonical resting-buy run. The dedicated Shell entry point, Ruby verdict, and isolated result fixtures are retired; shared SQL remains a Java test input. |
| R14 Gateway same-owner recovery | Keep one restart, retained FIX session/WAL, reconnect/resend, original-order retry, and singular durable effects. A successful database replay does not prove protocol recovery. | Deployed Jupiter verified the selected restart, FIX recovery, and unchanged business facts. Existing WAL/session tests own file integrity. The old node-local WAL prefix matrix and continuous sampler are retired, not copied. |
| R15 Matching business recovery | Keep one selected-owner replacement with replay progress, a new cancel, and explicit byte-identical redelivery with singular downstream effects. Ready alone cannot prove recovered business processing. | Deployed Jupiter verified the selected owner, replay, cancel, and controlled redelivery. The dedicated Shell/Ruby reports are retired. Native replay and service inbox tests retain independent local seams. Do not claim that the restart caused redelivery. |
| R11 critical-consumer failure | Keep actual deployed fail-closed and eventual durable-delivery behavior. The old mixed-outage runner is not equivalent to a normal restart. | Deployed Jupiter verified one actual PostgreSQL outage, held Matching publication, ingress rejection, and durable recovery. The mixed consumer-Pod outage runner and its static sequence/verdict contracts are retired under the P0 decision. No simultaneous Account/Persistence Pod failure is claimed. |
| R12 Gateway close/open safety | Keep authenticated transitions, actual eligibility, automatic stale-observation pause, restoration, and terminal day close. Successful OPEN alone cannot prove these distinct protections. | Deployed Jupiter verified the selected open, pause, real FIX rejection, recovery, and terminal close. Gateway service tests retain policy branches. The old terminal-close runner and all-partition consumer-drain matrix are retired. |

The canonical resting-buy run proves the selected R11–R15 live behavior.
The dedicated R11–R15 Shell/Ruby scenario chains are retired after their
last-caller and independent-value reviews. The Query runner still consumes
shared observation helpers, so these helpers remain until the P4 cutover.

## P4 Query disposition before implementation

| Responsibility | Decision and owner | Remaining gap or intentional loss |
| --- | --- | --- |
| R18 exhaustive Python route/read matrix | Retire the duplicate matrix when its runner moves. Keep `QueryOrderHttpTest`, `QueryExecutionsHttpTest`, `QueryAccountSummaryHttpTest`, and `QueryMarketHttpTest` as HTTP/PostgreSQL authorization owners. | Keep a basic authenticated deployed read of actual projected business data. Service tests do not establish Kafka-to-HTTP deployment wiring. |
| R18 obsolete Redis response seeding and comparison | Retired the deployed legacy-cache fixtures, old Redis-key mutation and verifier check. Existing HTTP tests prove no cache reads, fills, clears, or fallback to foreign/stale envelopes. | No separate deployment-wide legacy-cache mutation report. This retires completed compatibility verification, not PostgreSQL-only reads, real Redis outage, or fail-closed ownership. |
| R18 Python collector selection contract and Java process wrapper | Retire with the old fixture-discovery collector. The new observation must select its own actual order/event identities. Do not port scratch fixture discovery or hardcoded migration-version assertions. | `QueryCollectorPostgresContractTest` currently launches Python; it is not an independent service requirement. Its removal waits for the collector's last consumer. |
| R18 replay/isolation and R19 policy lifecycle | Keep unresolved until their runtime boundaries are reviewed. Reuse existing rebuild and policy tests for local behavior. | `QueryAccountPolicySnapshotHttpTest` explicitly tests a separately activated snapshot, not effective revocation or replica convergence. It cannot justify deleting the deployed lifecycle requirement. |

### P4 R18 duplicate deployed read-matrix retirement

The deployed Query runner no longer executes the separate Python
100-plus-observation authorization matrix per Pod or creates
`read-matrix.json`. The associated Shell collector and redundant
`deployedReadMatrix` Java verdict check have been removed. The
`query-security-phase1/2` verdict version is retained and now contains
**ten** checks rather than eleven. The previous eleven-check R18
checkpoint and the original twelve-check #194 PASS are historical
source-scoped reports, not evidence produced by the current runner.

The required basic actual Kafka-to-HTTP evidence remains:
`run_matching_fixture` submits the real public FIX cross,
`wait_for_query_fixture` selects its completed Order/Execution from the
durable Query projection and PostgreSQL ownership, and
`capture_query_snapshot` reads the selected Order, nonempty Executions,
Account summary and Market Reference through authenticated public HTTP.
The same business data is verified during actual Redis loss and after
bounded projection replay. R21 separately verifies two Query Pods'
readiness/liveness, immutable image/UID and authenticated operations
serving. Service HTTP/JDBC tests retain the denied-account, missing-parent,
minimal-envelope and no-store rules.

The loss is **intentional**: the deployed runner no longer reports the
exhaustive X/Y/missing-order cross-Principal matrix for both replicas or
an exact static policy-version 1 matrix count. These security behavior
rules remain in service tests and the still-active R19 lifecycle
components. `read_matrix.py` and `read_fixtures.py` cannot yet be removed:
`security_lab.py`, `operator_boundary.py` and a PostgreSQL collector
contract still import them. Their last-consumer review belongs to R19.
This R18 change does not prove effective revocation or two-replica policy
replacement; R19 still requires a separate source-aligned deployed run.

The scoped cutover passed the managed Query `postgresJdbcTest`, all 17
current Query CI contracts, the retained Python read-matrix and operator
boundary tests, the ten-check Java verifier, and static analysis. The
source/image provenance checks passed in an isolated clean source
snapshot, while `verifyAllScope` remains fail-closed. These are not a
new deployed Query acceptance run. The resulting tracked Shell inventory
is **113 files / 23,666 physical lines**, still above the minimum S5
maximums of **51 files / 10,476 lines**. No Python module was duplicated
or moved merely to reduce the apparent Shell count.

R19 keeps one actual two-replica policy replacement. Stop the old generation and verify that its tasks exited before activation. Verify revoked grants, disabled Principals, and old-credential rejection through both new replicas. Keep source/run/image binding and bounded catch-up for captured Account events published during the stop. Reuse existing authorization tests for the exhaustive route matrix.
Retire the Python Lab's separate approval database, event timeline, collector-credential export protocol, and restart/reinitialization simulation. Also retire its comment-only same-version edit, never-launched historical-image candidate, and deliberately unschedulable second replica. These verify a synthetic orchestration authority, not the startup-only product policy. The fresh-run/no-resume lifecycle must reject collisions and must never restore the revoked generation after a failure. This retirement loses the Lab approval-record and partial-activation recovery claims. It does not establish production revocation guarantees.

### P4 R19 synthetic negative-scenario cut

The first R19 cut removes the never-launched incompatible-image preflight
candidate, comment-only same-version change, simulated missing approval record
and runner reinitialization, and intentionally unschedulable second V2 Pod.
No second-Replica placement mutation or separate recovery attempt replaces
these tests. The older `rollback-rejections.json` and
`partial-activation.json` reports are no longer produced. This intentionally
retires the negative synthetic Lab authority/partial-activation assertions,
not the real Query startup-only policy enforcement.

The current Lab still performs its source-controlled image import, owned
namespace preflight, the old-generation stop and exact process drain, new
normal two-Pod activation, per-replica C→Y grant revocation and disabled-D
Principal checks, old-credential rejection, and bounded Account Kafka
catch-up after pause. Its separate Lab policy-approval record, ordered event
timeline and collector-credential export protocol **still have live code
callers** and remain an R19 unfinished migration obligation. Authority-only
tests and historical #193 evidence are not relabelled as product policy
revocation or fresh source-aligned deployment acceptance.

The retained 17 Query lifecycle CI contracts and interim policy-authority
test passed. Managed PostgreSQL/JUnit and Query Java suites, static analysis,
clean-source provenance and the S5 fail-closed preflight also passed.
No new source-aligned Kubernetes policy replacement was executed. This cut
leaves **113 tracked Shell files / 23,664 physical lines**, four Ruby
files, and 28 Python files. The S5 minimum maximums of 51 Shell files
and 10,476 Shell lines remain unmet.

For R16, retain one actual deterministic projection replay observed through the public snapshot API. Also retain a real Redis outage with durable pending repair and convergence after restoration. `MarketDataProjectionApplicationServiceTest` owns projection transactions, replay identity, conflicts, gaps, and reset scope. `RuntimeMarketDataPublicationTest` uses a fake cache for failure/repair. It does not establish actual Redis integration. Reuse the existing snapshot observer instead of copying its protocol code.
For R17, retain actual public-stream termination, replacement, resubscription, and a fresh complete snapshot through `MarketDataSnapshotRecoveryObserver`. `MarketDataGrpcServiceTest` owns request routing only. Retire the independent all-partition consumer-group ownership report and forced alternate-worker placement from this stream scenario. These add no separate basic public-stream requirement. This cutover makes no full-partition or worker-failure claim. Existing immutable identity, bounded failure, restoration, and cleanup requirements remain.

### P4 R17 public-stream ownership cut

The existing `run-streamer-recovery-certification.sh` remains the one
selected deployed R17 owner until a source-aligned final run succeeds.
Its public Java `MarketDataSnapshotRecoveryObserver` still observes an
initial complete gRPC snapshot, actual stream termination during an
ordinary `Recreate` Deployment restart, a new streamer Pod UID, a
second connection and subscription, and a fresh complete snapshot.
The scenario still uses the same client and local gRPC port, a bounded
window, lifecycle-owned namespace and retained source/image provenance.
Its cleanup still restores the temporary projection replay interface.

The runner no longer patches a `kubernetes.io/hostname` selector to force
another worker, nor does it capture Kafka consumer-group partition
ownership before/after replacement. Those requirements have no
independent R17 acceptance value. A replacement on the original worker
can pass if its UID differs and the old Pod disappears before the
new owner becomes Ready. The verdict no longer emits `nodesDiffer` or
`partitionAssignment`. No all-partition rebalance, worker-loss or
Kafka full-topic ownership claim follows from this scenario.

Before mutation the runner checks the immutable deployed image
against its retained image lock and explicitly requires one
`Recreate` replica. It also requires each observed owner Pod to be
Ready and to use the locked container image. The old Pod's absence
is read through a successful Kubernetes Pod-list response, not inferred
from a possibly unrelated failed `kubectl get`. The focused contract
protects these conditions, the same-node case, the UID change and
the existing port-forward/observer handoff. CI now runs that contract.
The independent Java observer remains unchanged.

This is a scope reduction and code-level test result, not a fresh live
replacement PASS. The separate R16 actual Redis outage/repair and
public replay requirement is not retired. Complete source-aligned
R16/R17 acceptance remains pending.

### P4 R16 source/run provenance and failure cleanup

The existing `run-certification.sh` remains the sole selected R16 deployed
owner for actual deterministic replay through the public gRPC snapshot
API and a real Redis outage with durable PostgreSQL pending-repair and
convergence after restoration. The Java `MarketDataEvidenceVerifier` still
owns its seven named business checks. No duplicate public gRPC protocol
implementation or extra Redis outage has been created.

The runner now requires the retained production-like namespace manager,
matching namespace run ID, an exact clean source revision and verifier
provenance, and immutable Market Data projection/streamer Deployment
images from the retained source lock **before mutation**. This replaces
the previous report-only Git HEAD label with actual fail-closed
source/run/image binding. The retained run path uses the existing
`SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR` override when required.

The runtime now records mutation-possible flags **before** scaling Redis
or projection or setting the temporary authenticated replay environment.
Restoration flags clear only after a successful operation, allowing the
EXIT cleanup to retry incomplete restoration and to reject an incomplete
run. After a successful explicit restoration the EXIT trap does not
repeat its rollout probes and risk invalidating an already finalized
verdict. This changes failure handling, not the deterministic replay,
source Kafka offset reset, durable pending-repair, Redis repair, or public
snapshot assertions.

The R16 live Kubernetes scenario has not been rerun for this change.
Java and Shell contract tests establish local behavior only; a full
source-aligned deployment, including actual Redis loss and recovery,
remains mandatory for R16/P6 acceptance.

For R20, reuse `OutboxCdcIntegrationTest` for actual RF1/RF3 broker outage, connector pause/recovery, and worker crash with captured source checkpoints and exact crash-induced redelivery. P3 owns deployed PostgreSQL interruption; R16 owns deployed Redis outage/repair. Retire the duplicate Kubernetes Connect-loss orchestration and its dedicated fixtures after their last callers move. This loses the separate Kubernetes Connect Pod/worker-loss claim; component recovery is not Kubernetes HA evidence.
For R21, merge Query's Redis-outage read check with actual readiness, liveness, authenticated serving, and stable Pod UID/restart-count observations across the configured liveness window. Reuse that same outage and restoration; do not create another placement deployment. Retire repeated placement/report-shape fixtures and exact static probe snapshots. Keep the live probe/serving boundary: a successful business read alone cannot establish liveness or readiness.

For R22, native CTest remains the integrity, bounded replay, and capacity owner. Merge the separate deployed fleet/image inventory into the existing Kubernetes runtime observations. Retire its independent benchmark/report merge and optional CPU-affinity certification wrapper; make no CPU-pinning or production-performance claim.
The old Kafka failure launcher only reclassifies topic/configuration metadata after one broker loss, two broker loss, and restoration. Retire those three classification runs with the old profile-report machinery. Keep actual topology assertions and RF1/RF3 outage/redelivery behavior in `OutboxCdcIntegrationTest`; these do not establish native Matching process-crash recovery.
P3's selected Matching Pod replacement does not close R22's abrupt process-crash boundary. Keep that boundary unresolved until its necessary actual crash/recovery behavior has an owner. Do not infer crash-induced replay from graceful replacement or the separately controlled duplicate event.

## P5a acceptance

Create the root `verifyAll` task and connect existing ordinary Java suites, static analysis, native CTest, manifest, dependency/Flyway, local FIX certification, and Kubernetes owners.
No test assertions move into Gradle. Complete selection defaults native checks to `full-native-dev`.
At the original P5a checkpoint, the entry had to fail before build/deploy/test work because retained P3/P4 requirements had no new owner.
Name those unresolved requirements in a small explicit preflight list; provide no property that bypasses it.
Dry-run must show actual owner tasks, with Query's ordinary suite and collector scheduled once each.
Actual invocation must fail at scope preflight before root-build service tests, native work, or deployment, and leave native evidence absent. Normal Gradle included-build preparation remains permitted.
This is early wiring, not P5b completion or a complete-local PASS.

P5a focused evidence: the task was initially absent (`/tmp/simplematch-202-p5a-red.log`).
The new dry-run schedules existing owners (`/tmp/simplematch-202-p5a-graph.log`).
Actual invocation and `--continue` both fail at scope validation with no service/native/deployment task executed; the selected native evidence path remains absent.
The explicit preflight remains blocking. P3 now has selected live owners, but
its new test assertions still require final-source acceptance. P4/P5b/P6
requirements also remain unresolved.


## Final Ruby and Python removal condition

The owner requires all project Ruby and Python code removed at final delivery: standalone files, embedded programs, runtime dependencies, dedicated fixtures/tests, and callers.
Do not translate each script into Java. Retire obsolete, duplicate, source-layout, and coverage-only responsibilities; reuse existing Java owners for necessary behavior.
Historical results retain their original language and scope descriptions, but no project executable Ruby or Python path remains.
After the R11–R15 retirement slice, the working tree contains 4 tracked `.rb`
files and 28 tracked `.py` files, plus embedded programs and their callers.
These counts describe the removal scope, not a target for equivalent Java files.
Assess each test before changing it. Delete tests with no independent value instead of translating them.
Keep necessary command safety, authorization, credential handling, lifecycle ownership, and cleanup behavior under suitable existing Java owners where possible.
Do not introduce a different scripting language as a replacement.

The Python inventory contains six Query certification library modules, four fixture/policy/lifecycle entry points, seventeen dedicated contract tests, and one recovery HTTP fixture.
Review the seventeen tests individually against retained requirements and existing service/tool tests before deciding their disposition.
The public FIX scenario must use a Java-owned synthetic credential/TLS fixture instead of introducing a new dependency on the Python bundle provisioner.
Classification and removal accompany each delivered responsibility. Update the Issue only after a whole sequence milestone completes.

Ruby test dispositions before migration: retain hard-reset's `healthy`, `absent`, and `dry-run` cases for actual mutation selection. Retain `missing-kind`, `discovery-failed`, and `observation-failed` because failed discovery must stop destructive work. Retain `invalid-role` for identity refusal. Retain `registry-refused`, `namespace-api-failed`, and `pv-api-failed` for failure propagation at distinct cleanup boundaries. Drop exact human-message matching; preserve exit/mutation behavior at the actual command entry point.
For resource budgeting, keep phase-peak calculation, replica/init-container accounting, malformed-quantity rejection, and sufficient/insufficient capacity cases. Retire the old CLI's warning-only over-budget mode and exact legacy report-layout checks when that CLI loses its final caller. The Kubernetes preflight must remain fail-closed; do not build a second resource report framework.

## P3 current implementation and acceptance

`KubernetesBusinessEnvironment` owns build-receipt checks, setup, diagnostics, runtime identity, and exact cleanup for the selected profile.
`resting-buy` runs Account, Risk, Persistence, Gateway, and all 15 Matching owners; RM-1 retains its smaller scope.
The profile selects its service-owned Flyway Jobs and completes them before business startup.
`KubernetesConnectors` registers and observes the Risk and Account CDC owners.

`RestingBuyKubernetesTest` reuses Gateway's `PreparedFixSubmission` test fixture for the public BUY, same-claim Gateway recovery, same-claim Matching recovery, cancel, and exact event redelivery.
`KubernetesCriticalDelivery` owns the PostgreSQL outage and fail-closed ingress scenario.
`KubernetesTradingClose` closes the same session last and observes terminal business effects.
The canonical resting-buy run passed these selected deployed behaviors.
The run did not complete the legacy caller and fixture cutover.
The requirement dispositions above govern the retained assertions and intentional verification losses.

`KubernetesFixCredentials` provisions private server/client identities with JDK keytool and binds one synthetic counterparty to the run's account.
The fixture checks actual mutual TLS and cleanup without reproducing the Python Query policy framework.
`KubernetesPostgres` owns SQL transport and bounded convergence.
`KubernetesMatchingEvents` reuses the existing Kafka observation/redelivery implementations.
Run-owned forwarders stop their own processes; authenticated Gateway operations use actual live eligibility.
Verification fingerprints include shared FIX fixtures and executed SQL; image fingerprints exclude test-only inputs.

The local Matching request is 1 GiB per main container; its limit remains 2 GiB and the fleet remains 15.
The selected profile's 24.367 GiB steady request plus helper/control-plane reserve requires 28.867 GiB.
Docker Desktop reports 37.275 GiB RAM. Swap does not increase schedulable guest RAM.
The bounded [live-03 memory observations](../../out/verification/issue-202-p3-public-live-03/matching-memory.tsv) supported the local request adjustment; they are not load or production sizing evidence.

The Kubernetes-tool suite and global static analysis passed after terminal-close integration.
Live-07's first preparation stopped for the owner's strict two-axis review. It created no test cluster or deployment ownership receipt. Its refreshed preparation uses a new reviewed build receipt and preserves the interrupted log; the deployed result still uses the fresh live-07 directory.
The review found no confirmed Standards violations and two Spec defects: missing before/after retry equality and a pause window longer than native liveness permits. The fixes compare existing business facts, preserve baseline evidence, and cap paused work at 20 seconds with an independent 10-second restoration budget. The Spec reviewer checked both fixes; the tools suite passed 55 cases with no skips, and global static analysis passed. At that checkpoint, live execution was still required. The later canonical PASS is recorded below.
Earlier failed results remain unchanged. Live-05 and live-06 cleanup passed; each removed only its four nodes and four disposable volumes.

### P3 live acceptance and cutover checkpoint (2026-11-04)

The canonical disposable `resting-buy` run passed on the native Docker Engine.
Its run ID is `f0266058-ab47-45f8-84dc-ca85551fda1b`.
The evidence directory is `out/verification/issue-202-sata-canonical-resting-20261104-1407/`.
The `summary.json` records PASS for assertions, diagnostics, cleanup, and overall status.
All 15 Matching owners became Ready. The run used no manual NetworkPolicy change.
The test used one actual PostgreSQL outage and restored owned resources.
This PASS covers the selected Jupiter scenario, not the complete S5 contract.
The tested Git tree remained unchanged when 26 unpublished commits became 12 commits.

| Requirement | Deployed Jupiter evidence | Remaining cutover boundary |
| --- | --- | --- |
| R11 | `KubernetesCriticalDelivery` holds the routed command, rejects an actual FIX order during ingress pause, removes PostgreSQL, and verifies durable recovery. | The old simultaneous consumer-Pod-loss runner was retired. No combined Pod failure or broader outage matrix is claimed. |
| R12 | `KubernetesGatewayAdmission` and `KubernetesTradingClose` verify opening, automatic pause, recovery, close barriers, and selected terminal effects. | The old close runner and all-partition high-watermark predicates were retired. Keep Gateway policy tests. A reviewed Java assertion now checks each closed owner's runtime state, schema, and unique partition identity. |
| R13 | `RestingBuyKubernetesTest` verifies one real FIX BUY, Risk admission, order delivery, Persistence, Account, and consumer inbox identity. | The legacy normal runner and Ruby verifier are retired. Java still reads the shared SQL observations; keep source-fingerprint coverage. |
| R14 | `KubernetesGatewayRecovery` replaces the owner. The test compares admission, delivery intent, and durable state after a retry. | The old WAL/journal prefix and sampler assertions were intentionally retired. Keep independent Gateway WAL and session integrity tests. |
| R15 | `KubernetesMatchingRecovery` verifies selected-owner replacement, replay, a new cancel, and controlled byte-identical redelivery. | The old recovery CLI/verdict is retired. Keep Native replay and inbox tests. Do not infer full-fleet crash recovery or restart-induced redelivery. |

The old R11–R15 runners and their dedicated contracts had no active caller
after the owner-approved coverage reductions. The original runbooks now
describe retired historical commands, not supported entry points.
The shared SQL observations remain direct Java test inputs.
The canonical live PASS predates this cutover and the new Java close assertions.
It is not a new
source-aligned deployment of the modified tree.

| Legacy group | Caller disposition | Retained value or intentional loss |
| --- | --- | --- |
| Normal and recovery Shell entry | Removed after reviewing the internal Ruby/helper chain and the last legacy CLI option-conflict test. | The actual public FIX/order/recovery behavior has one deployed Jupiter owner. No substitute legacy CLI is retained. |
| Gateway recovery Ruby and fixtures | Removed the sole Shell caller, Ruby unit tests, and result baselines together. | `WalRecoveryJournalTest`, Gateway session tests, and Jupiter retain distinct integrity and business checks. The old node-local WAL prefix and owner sampler matrix is intentionally lost. |
| Matching recovery Ruby and fixtures | Removed the sole Shell caller, Ruby unit tests, and result baselines together. | Jupiter owns selected-owner replay, new cancel, and redelivery; Native replay and service inbox tests keep their independent seams. |
| Shared Shell/FIX/Kafka adapters | Renamed the former resting-buy adapter test to `shared-interface-contract.sh` and removed only obsolete recovery-specific assertions. | Preserve authenticated Gateway operation, automatic-close restoration, port-forward lifecycle, bounded calls, correlated `SENT` completion, and FIX state ownership. |
| Dedicated recovery lifecycle and error contracts | Removed with their sole Shell recovery implementation. | No replacement test for a removed CLI deadline, sampler, or cleanup implementation. Active helpers keep their own tests. |
| Critical failure runner | Retired `run-failure-certification.sh`, the source-order `deployment-contract.sh`, and the runner-specific `verdict-contract.sh`. Updated CI and the historical runbook. | R11 Jupiter covers actual PostgreSQL outage and recovery. The simultaneous Account/Persistence/Gateway Pod-loss, two-second open-to-send, and separate FIX retransmission matrix are not new S5 acceptance claims. Shared Risk/FIX/Kafka Query helpers remain. |
| Terminal close runner | Retired `run-gateway-close-certification.sh`, its private predicates, and `gateway-close-contract.sh`. Updated CI and the historical runbook. | Jupiter covers all Close Barriers, 15 closed owners, selected terminal event, reservation release, and absence of quarantine. Retired the broad consumer-offset and fixture matrix. Query's live projection reader moved to `query-service/lib/order-projection.sh`. |
| Shared SQL observations | `RestingBuyKubernetesTest`, `KubernetesCriticalDelivery`, `KubernetesTradingClose`, and `KubernetesMatchingRecovery` still read the legacy SQL directory. | Keep existing input paths and source fingerprinting until any coordinated migration. Removed only `gateway-session-state.sql`, whose sole reader was the retired Shell recovery helper. |

P3's selected live behavior and R11–R15 legacy runner dispositions are recorded.
The milestone two-axis review and final validation must still close the P3 slice.
It does not require another identical deployment to prove this selected business flow.
Ruby resource budgeting and the remaining Python/Ruby consumers remain explicit retirement work.

#### R13–R15 retirement review (earlier checkpoint)

**Standards:** The retained critical-consumer Shell contracts passed.
The verifier provenance contract also passed in an isolated, clean snapshot
of this change. The workflow YAML parses, all active CI command paths exist,
and `git diff --check` passes. The Kubernetes manifest test passed.
Kubernetes unit tests, Gateway tests, and static analysis passed with cached
task results. No production Java code or Kubernetes manifest changed.

**Spec:** The canonical live PASS proves the selected R13–R15 integration.
It predates this Shell/Ruby retirement and does not certify the new Git tree.
The retired node-local WAL prefix, owner sampler, old report baselines,
and obsolete CLI-specific failure cases are intentional coverage losses.
The Java WAL/session tests and shared Shell/FIX/Kafka contracts remain.
At this earlier checkpoint, R11/R12 legacy runners still needed independent review.
`verifyAllScope` still fails as designed for unresolved S5 requirements.

#### P3 R11–R15 cutover checkpoint

The R11 and R12 final-caller review found no requirement to preserve their
dedicated Shell deployments. The P0 decision retains one actual PostgreSQL
outage and one terminal close in the shared Jupiter resting-buy scenario.
The retired R11 runner also stopped Account, Persistence, and Gateway Pods.
That combined failure claim is intentionally lost. Its separate FIX send
timing, retransmission, static sequence, and verdict checks are not new S5
acceptance criteria. `KubernetesCriticalDelivery` retains the actual
PostgreSQL loss, fail-closed FIX rejection, held command, and durable recovery.

The retired R12 runner checked a separate full-topic consumer drain and
quarantine history. P0 replaces that matrix with the selected order's exact
terminal event in three consumer inboxes and the corresponding business facts.
`KubernetesTradingClose` keeps all 15 Close Barriers and closed Matching owners.
The review found a missing Java closed-runtime guard. The Java test now
requires schema version 1, runtime state `RUNNING`, a matching Pod identity,
and 15 distinct partition IDs before it accepts the closed-owner evidence.
The existing canonical `close-facts.json` independently contains all these
facts. This is an examination of historical evidence, not a new live run.

The two old runners, three dedicated Shell contracts, and the close-only
predicate helper are removed. Shared Query consumers still require
`failure-support.sh`, `failure-recovery.sh`, and other critical-consumer
adapters. Keep them until P4 changes their last callers. Query's projection
reader now resides in `query-service/lib/order-projection.sh`. Its two
functions are byte-identical to their old definitions. No current executable
caller references either retired runner or the old close helper.

Focused checks passed after this cutover: retained critical-consumer Shell
contracts, 16 Query contracts, the provenance contract in a clean temporary
source snapshot, Kubernetes Jupiter unit tests and manifest test, Gateway
tests, and static analysis. Gradle reported 125 tasks, with three executed
and 122 up-to-date. The CI workflow parses and all 30 command paths exist.
`verifyAllScope` still fails before complete verification as intended.
The final S5 source-aligned live deployment is a P6 obligation.

The tracked working tree now contains 123 Shell files and 25,901 physical
Shell lines. It also contains 4 Ruby files and 28 Python files.
The Issue #202 minimum reduction gates (51 Shell files and 10,476 lines)
remain unmet. No code was translated into another language for this metric.

#### P4 R18 obsolete Redis-cache check retirement

The current Query certification runner no longer writes legacy response
envelopes into Redis or compares those obsolete keys after business reads.
The two Shell functions, their evidence files, and the dedicated verifier
check are retired. `QueryEvidenceVerifier` now evaluates eleven retained
checks without the `legacy-cache.json` input. The existing contract version
remains `query-security-phase1/2`; the removed check is an intentional
verification-interface reduction with no remaining executable caller.
The historical twelve-check #194 PASS remains unchanged and source-scoped.

The runner still performs its actual Redis outage and compares PostgreSQL
business responses across baseline, outage and rebuild. Query HTTP tests
retain security and cache-non-use checks. The Python per-replica route matrix,
collector, and policy lifecycle still have active callers and are not
retired by this slice. R18 deployed business-read coverage and R19 two-replica
policy replacement therefore remain open for P4 integration.

#### P4 R22 deployed-report and capacity-wrapper retirement

`scripts/run-matching-deployed-certification.sh` had no active project caller.
Its sole command dependency was `scripts/run-matching-capacity-certification.sh`,
which also had no separate active caller. CI, Gradle, and the full-local phase
graph do not invoke either entry point. S5 retires both wrappers and their
separate CPU-affinity, Pod/image inventory and aggregate report checks rather
than translating them into Java or requiring their optional evidence format.

`./gradlew nativeTest` still runs the native CTest capacity benchmark and
integrity/replay checks. The benchmark binary and CMake target remain.
`run-matching-e2e-certification.sh` and the shared `matching-e2e.sh` functions
remain unchanged because existing fault-mode scenarios and their contracts
use them. Kubernetes/Jupiter still observes deployed Matching owner identity,
selected recovery, and terminal close. None of these tests now claims the
retired CPU pinning check, whole-fleet deployed report, production performance,
or full-topic catch-up. R22's abrupt process-crash recovery requirement is
still unresolved for final-source complete verification. Dated historical
benchmark and deployed-collector results remain unchanged and non-transferable.

After the R18 and R22 retirements the tracked worktree contains **121 Shell
files and 25,547 physical Shell lines**. The minimum S5 gates remain **51
files and 10,476 lines**. This change removes 2 Shell files and 312 lines
from the R18 checkpoint without creating replacement scripts. It does not
close P4, P5b, P6, or the full S5 acceptance gate.

#### P4 R22 obsolete Kafka broker-classification phase

`kafka-broker-failure-live` was the only active caller of
`scripts/run-matching-kafka-failure-check.sh`. That script stopped one and
then two Compose brokers, captured topic/configuration metadata, and invoked
the existing topic-profile validator. It did not publish or verify actual
business events through the broker interruption. The amended R22 disposition
retires this redundant three-state metadata classification run; it does not
replace the launcher with a Java translation.

The full-local phase graph now selects `kafka-profile-validation` directly
after Compose/topic setup. `compose-down-before-kubernetes` depends on that
validation, so actual broker/topic/producer configuration checks remain
fail-closed before the cluster transition. `OutboxCdcIntegrationTest` retains
RF1/RF3 broker outage, recovered publication, connector resume, source
checkpoint and crash-induced redelivery checks. Neither the static profile
check nor this CDC test establishes native Matching process-crash recovery.
The old broker-loss metadata PASS/reject/recovery report is intentionally
retired. Historical reports still represent their original source and scope.

Following this removal, the tracked worktree has **120 Shell files and
25,403 physical Shell lines**. The minimum S5 gates of 51 files and 10,476
lines are still unmet; P4/P5b/P6 and R22 abrupt process-crash proof remain
open. No new deployed recovery or performance claim follows from deletion.

Focused validation executed the retained `OutboxCdcIntegrationTest` for RF1
and RF3: two tests passed, with no failures, errors, or skips. The actual
full-local dry-run preserves the order Compose setup → Kafka profile
validation → Compose teardown → Kubernetes namespace creation. The existing
fresh-execution/skip-semantics contract, ShellCheck, and source/image
provenance contract in an isolated clean snapshot also passed. These checks
do not constitute a live Kubernetes Matching process-crash acceptance run.

#### P4 R20 Kubernetes Connect worker-loss diagnostic retirement

The original #156 worker-loss diagnostic required a dedicated Kubernetes
Connect two-worker scenario, including Pod deletion, task reassignment, a
fresh Account outbox transition and matching Kafka publication. Issue #202
expressly retires that separate orchestration claim. Its local command had
no current full-local or CI caller; only its private scenario, REST tunnel,
CLI and fixture tests remained. The dedicated seven Shell files and their
Java `connect` command/verifier/classes/test fixtures are removed together.
The original #156 report is historical evidence for the exact old source and
is not relabelled as new CDC or Kubernetes HA certification.

Keep `OutboxCdcIntegrationTest` as the real RF1/RF3 owner. It exercises
actual broker unavailability, connector pause/resume, source checkpoint,
worker process crash and crash-induced redelivery against the test
dependencies. It does **not** replace Kubernetes Connect Pod or task
reassignment coverage. `cdc-verifier.sh`, `run-risk-cdc-delivery-observer-check.sh`,
the retained CDC focused diagnostic, and `local-resilience.sh` still have
active callers and remain unchanged. The scoped CDC verifier fingerprint
no longer lists deleted Connect-only inputs; its runtime fingerprint and
fail-closed source checks remain in place.

After this retirement, the tracked worktree contains **113 Shell files and
24,166 physical Shell lines**. The 51-file/10,476-line minimum gates are
not met, and the full R16–R22/P5b/P6 acceptance gate remains unresolved.
No Kubernetes Connect HA or final-source deployed PASS follows from this
removal. The deleted Shell-specific tests have no surviving code contract;
their assertions were not translated into another language.

Focused validation of the cutover passed: the retained Java recovery verifier
suite and static analysis, both RF1/RF3 `OutboxCdcIntegrationTest` cases
(2/2, without skips), `manifestCheck`, the shared CDC payload verifier and
observer fixture contracts. The CDC source-signature and focused-diagnostic
contracts passed in isolated clean Git snapshots containing the full deletion.
They correctly rejected the incomplete dirty worktree while the Git index
still named removed Connect sources. No provenance bypass was introduced.

#### P4 R21 shared Query Redis outage and Pod-health verification

The separate `run-local-java-placement-serving-check.sh` owned a second Redis
outage, dedicated port-forward adapter, placement report generator, Java
validator and 22 report/probe fixtures. Last-caller review found no active
CI/Gradle/full-local use of that deployed runner; its remaining callers were
the placement-only Java test and two Query HTTP adapter tests. The old runner,
adapter, five Java placement-only classes, dedicated test and fixtures have
been retired together. The two HTTP tests were reviewed: the useful
credential/anonymous-health boundary is exercised at the **new** Query
observer seam; the independent 600-second wrapper-budget check was removed.

The retained `run-certification.sh` now owns R21 within its **existing**
Redis outage and restoration. Before mutation it captures both Ready Query
Pods, distinct nodes, immutable image identities, and the deployed liveness
probe configuration. It requires an observation window of at least 60 seconds
and no shorter than the actual liveness window, allowing up to 120 seconds.
During the observed zero-Ready-Redis window it retains authenticated
PostgreSQL business reads, then verifies both Pods' readiness, liveness and
operations-authenticated serving through direct Pod port forwards. It
requires identical Pod UID, node, image ID and restart count before the
outage, after its window, and following Redis restoration. A missing/unhealthy
Pod, failed probe, observed Redis recovery or changed identity rejects Query
certification. The existing cleanup restores Redis on any failure.

The newly scoped Shell contract checks negative restart, NotReady, failed
HTTP/body and Redis-presence cases. Existing Query HTTP tests retain the
operations credential, anonymous health, no-store and response schema checks.
The old independent placement-report JSON/schema and duplicated Pod-probe
fixtures are intentionally lost. The new raw observations are scoped to the
Query run and do not independently constitute a deployment PASS.

Current tracked worktree totals after this cut are **113 Shell files and
23,643 physical Shell lines**, plus four Ruby and 28 Python files. Neither
the 51-file nor the 10,476-line minimum has been reached. R18/R19 other
deployed requirements, R16/R17, R22 abrupt process crash, P5b and P6
final-source acceptance remain open.

#### P3 milestone Standards / Spec review

**Standards:** Review used `HEAD` (`3b340a6`) as the fixed point and compared
the complete working-tree diff. The independent Standards worker did not
finish, so the primary review checked repository workflow rules, the
bad-smell list, CI callers, and the affected interfaces directly. No new
production-service interface, second test framework, or duplicate Query
projection implementation was introduced. The shared SQL inputs and their
source fingerprints remain. Focused tests, source-aligned provenance,
workflow path resolution, and `git diff --check` passed. The `graphify`
command is not available on this host, so its update was not executed.

**Spec:** An independent review compared this diff with Issue #202 and P0.
It found one stale historical review status, which is now marked as an
earlier checkpoint. It found no confirmed executable requirement regression.
The selected live R11–R15 evidence and every intentional verification loss
are recorded above. The Java close guard matches the retained close facts.
This review closes the P3 ownership and old-runner cutover decisions, not
the fresh final-source S5 acceptance requirement. `verifyAllScope` remains
fail-closed for R11–R22 pending final integration and verification.

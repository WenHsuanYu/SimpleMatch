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

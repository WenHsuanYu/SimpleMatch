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

The transaction suites use disposable H2 databases.
Their results do not establish PostgreSQL or deployed Kubernetes behavior.
The existing `verifyPostgres` and `verifyKubernetes` owners retain those distinct scopes.
No service implementation, immutable analysis policy, historical report, or runtime deployment changes in this retirement group.

Current documentation removes the retired commands.
The frozen S0 inventory and baseline remain historical records of the old paths.

# S0 execution and deletion plan

Status: analysis deliverable for [#197](https://github.com/WenHsuanYu/SimpleMatch/issues/197).
Program authority: [approved specification #196](https://github.com/WenHsuanYu/SimpleMatch/issues/196).
The owner authorized S0 analysis. No executable deletion or deployment occurs in this slice.

## Acceptance and affected scope

S0 must account for all 170 baseline files and 34,923 physical lines.
It must connect requirements, callers, replacement owners, deletion conditions, defects, and resource limits.
The [file ledger](script-ledger.md), [requirement ledger](requirements.md), and [resource assessment](resources.md) supply that evidence.
The [JSON ledger](script-ledger.json) records per-file hashes, references, batches, and budgets.
The [bounded-check record](evidence.json) distinguishes observed failures from unexecuted runtime behavior.

Affected future modules include all seven Spring services, shared test/build conventions, existing Java evidence tools, and native Matching test entry points.
Deployment inputs, local operations, CI workflows, hook callers, and current evidence readers also participate.
S0 only adds planning artifacts. User-owned changes and executable inputs remain intact.

## Reproducible reduction budget

The fixed baseline commit is `7b9d2fd035632aa6797ad1d771a90c96b5062a87`.
Every inventoried current Shell byte sequence matched its baseline Git object during this audit.
No later tracked Shell addition or deletion exists at this S0 snapshot.
Use LF count plus one for a nonempty final line without LF. Include comments and blank lines.

| Delivery owner | Baseline files | Baseline lines | Retained existing files | Retained line ceiling | Planned deletions | Planned deleted lines |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| S1 | 2 | 111 | 0 | 0 | 2 | 111 |
| S2 | 11 | 1,566 | 3 | 442 | 8 | 1,124 |
| S3 | 26 | 4,976 | 17 | 3,386 | 9 | 1,590 |
| S4 | 23 | 3,574 | 6 | 912 | 17 | 2,662 |
| S5 | 108 | 24,696 | 2 | 253 | 106 | 24,443 |
| Total | 170 | 34,923 | 28 | 4,993 | 142 | 29,930 |

The retained budget includes required imported helpers, not just top-level command names.
For example, the retained hook needs Flyway inventory. The cluster manager needs resource collection and reporting.
Deleting those imports would create an artificially small but unusable budget.
Retained verification commands must delegate authoritative assertions or narrow to operator observations.
Their current line totals are conservative ceilings, not permission to keep business tests in Shell.

Reserve at most six additional Shell files and 1,991 lines for demonstrated environment-operation needs.
That produces a target ceiling of 34 files and 6,984 lines.
It removes at least 80% of files and approximately 80.001% of lines from the fixed baseline.
The existing-only budget would remove approximately 83.53% of files and 85.70% of lines.

The absolute minimum gate remains 51 files and 10,476 lines.
The difference from the target is contingency, not an automatically approved expansion budget.
Each slice must report necessary additions and the reason for any target drift.
The final program cannot pass below 70% reduction in either measure.

Only eight files, totaling 98 lines, currently have a direct retirement or compatibility-alias disposition.
Most savings therefore require actual responsibility migration and removal of old tests and scaffolding.
S0 does not promise that deleting unused files alone can meet the goal.
The replacement program is substantial, particularly S5. Keep its scenario batches explicit.

## Concrete scenario batches

The parent issues remain the agreed S0–S6 coordination slices.
Use the batch labels below for bounded implementation tickets before coding the broader S2 and S5 scopes.
The JSON ledger assigns each baseline file exactly one deletion owner.
Shared helpers can serve several requirements without having several deletion owners.
For shared paths, `replacementIntroductionBatch` identifies the first replacement delivery.
S5-G owns final path deletion after all consumers migrate. The human ledger shows both batches.

| Batch | Work and public verification seam | Exit and deletion scope | Required predecessors |
| --- | --- | --- | --- |
| S1-A | Seven-check Market Data evidence verdict. Use a lightweight Java evaluator and current synthetic JSON fixtures. | Both verdict Shell files disappear. Caller consumes the replacement. Add negative evidence for every retained condition and I/O failure. | S0 |
| S2-A | Exact outbox publication through PostgreSQL, Kafka, and Connect. Reuse the existing durable identity contract. | Replace Compose scenario, connector launch adapters and CDC assertions. Keep RF3 cases. Shared CDC paths remain narrow compatibility until S5-G. | S1-A. Resolve F-01 before claiming relevant CDC checks pass |
| S2-B | Service Flyway/JDBC verification, Gateway session SQL, and necessary index plans against disposable PostgreSQL. | Replace disposable CI orchestration. Preserve external read-only operators and versioned migration inventory. Gateway SQL fixture behavior follows its scenario owner. | S1-A |
| S2-C | Query TLS hostname/trust/authentication and command ACL tests. Use existing service fixtures. | Replace the certificate-preparation Shell wrapper with bounded fixture ownership and direct task invocation. | S1-A |
| S3-A | Image/registry/kind lifecycle through one real deployed RM-1 Risk-to-Matching scenario. | Replace RM-1 Shell scenario and lifecycle contract tests. Keep narrow operations. Prove both cluster modes, failure cleanup and exact ownership. | S1-A. platform inputs and capacity preflight |
| S4-A | Render local/test/staging/production profiles. Check schema plus necessary topic, probe, TLS, storage and verifier semantics. | Delete replaced manifest contracts. Keep the renderer path only as compatibility for remaining aggregate callers until S5-G. Preserve narrow topic operator entries. | S0. F-01 affects obsolete topic-source checks |
| S4-B | Active transaction/Account v2/offline-reference rules, hook selection and obsolete phase-checklist retirement. | Retire archived checks and obsolete structure assertions. Preserve current policy and hook operations. | S0 |
| S5-A | Critical-consumer fail-closed/isolation, Gateway close/open safety, and the public resting BUY baseline. | Replace delivered scenario logic and contracts. Shared helpers and the multi-mode BUY runner keep narrow compatibility until S5-G. | S2, S3, S4 |
| S5-B1 | Same-owner Gateway replacement with real FIX reconnect/resend and original-body business retry. | Replace Gateway recovery orchestration and obsolete predicates. Keep session/WAL/storage continuity and at-most-one-owner checks. | S5-A normal BUY and shared observations |
| S5-B2 | Matching owner replacement/replay, a new cancel, and controlled identical-byte redelivery. | Replace Matching business recovery and old observation plumbing. Preserve distinct recovery versus controlled-redelivery claims. | S5-A normal BUY and shared observations |
| S5-C | Current Query read/outage/rebuild/active-processing behavior and accepted policy-generation lifecycle. | Reuse the twelve-check Java evaluator. Remove old Shell collectors and obsolete Python lifecycle/fixture duplication with their replacement. | S5-A FIX baseline. S2-B/C. S3 lifecycle |
| S5-D | Market projection replay/Redis repair and public stream worker-loss/resubscription. | Remove deployed Market Data/streamer Shell orchestration and corresponding old contract scaffolding. | S1-A. S5-A shared collectors. S3 lifecycle |
| S5-E | Native capacity/local-depth integrity plus remaining Matching Kafka/restart/replay fault modes. | Retain native executables and CTest. Remove Shell benchmark loops and business verdicts when normal tasks own them. | S3. S4 topic/profile checks |
| S5-F1 | Dependency loss/recovery and focused Connect worker-loss/CDC diagnostics. | Reuse current Java recovery verdicts. Remove old Shell scenario/tunnel scaffolding after claims and restoration migrate. | S2-A. S3 lifecycle |
| S5-F2 | Authenticated Java serving/placement with existing minimal operator diagnostics. | Reuse current Java verdict. Replace long stateful Shell scenario and its adapter. | S2-C. S3 lifecycle |
| S5-G | Complete Gradle entry and shared provenance/result/observation cutover. | Remove aggregate Shell engine, aliases and now-unreferenced shared helpers. Integrate every retained requirement and audit all callers. | All applicable S5 scenario batches |

RM-1 proves durable admission/outbox through `matching.commands`.
It does not prove complete matching, filled trading, or replay. Its S3 use cannot satisfy those later scenarios.
The already documented normal BUY has zero fills. Query's cross fixture separately exercises filled and IOC behavior where required.
Native measurements retain their stated workload and hardware boundaries.

S3-A must integrate its replacement scenario while other legacy scenarios still run.
Use temporary compatibility only where a remaining caller needs it, with S5-G as the explicit removal point.
Do not maintain two authoritative verdict implementations for a delivered scenario.
Shared Shell collectors remain only until their final consumer migrates.
Their file-level deletion gate includes the full indexed caller set, not just the first migrated scenario.
This also applies to the multi-mode resting BUY runner, Gateway/Matching recovery helpers, shared CDC verifier, and manifest renderer.
Update delivered consumers to one authoritative implementation during introduction.
Temporary old paths may delegate to it. They must not retain a second verdict implementation.
An introduction batch must not wait for dependent scenario batches merely to remove a shared filename.
S5-G removes the final compatibility paths and records their counts.

## Findings and blockers

| ID | Finding and evidence | Classification | Required treatment |
| --- | --- | --- | --- |
| F-01 | `test-matching-topic-cutover.sh` exits 1 because it expects `create_kafka_topics()` in the old Kafka helper. Actual creation uses phase dispatch. | Reproduced existing static-check failure. [Draft PR #195](https://github.com/WenHsuanYu/SimpleMatch/pull/195) proposes a repair but is not merged at this audit. | S2-A/S4-A must resolve the stale check or replace it with equivalent current behavior before their affected gates pass. Do not merge the PR automatically. |
| F-02 | Real `cleanup()` ignores failed Compose cleanup. With a command stub returning 17, the process reports exit 0. | Reproduced failure propagation defect through the actual function. No Docker operation occurred. | S3/S5-G must preserve the primary failure and propagate cleanup failure. Add a regression at the replacement lifecycle seam before claiming success. |
| F-03 | Archived Phase 5 rejects internal Gateway HTTP client imports and internal observation URLs. It exits 1 although its message asserts an exchange-call defect. | Reproduced obsolete broad source matcher. No exchange call is established by this output. | S4-B retires this shape. Preserve the narrower offline-reference requirement separately. It does not block unrelated live scenario work. |
| F-04 | Existing default selection has 62 phases, while six dependent business/restoration scenarios remain separate invocations. | Source-confirmed automation gap against the new complete-entry requirement. | S5-G explicitly registers those retained requirements plus normal development checks. A deployment-only success cannot satisfy AC-04/AC-11. |
| F-05 | Full local profile declares 41.7421875 GiB and 6.875 CPU at the larger stage. Public standard hosted Linux provides 4 CPU/16 GB/14 GB SSD. | Reproduced offline declared-capacity mismatch. Runtime failure timing is unmeasured. | S6 complete-hosted acceptance remains blocked until sufficient capacity or requirement-preserving staging is verified. Larger paid capacity needs a separate authorized choice. |
| F-06 | Query #194 is CLOSED with final accepted synthetic Local Lab evidence. Part of the remaining-work inventory still describes its aggregate as pending. | Verified tracker/document discrepancy. This is not a newly reproduced runtime defect. | Use #194 final evidence/source and the current certification contract. Correct stale capability wording with its owning delivery context. Do not reopen closed security work or restore old Redis expectations. |
| F-07 | Dated 2026-10-20 certification predates the simplified runner and current schema. The current document explicitly denies a new PASS for that runner. | Known historical evidence boundary. Current complete execution was not run in S0. | S5/S6 require fresh source-aligned complete results. Preserve old reports without relabeling. |
| F-08 | Current daemon capacity, per-worker allocatable resources, image bytes, complete duration/RSS, private fixture availability, and actual cancellation cleanup are unmeasured. | Unexecuted prerequisites, not confirmed defects. | Measure after deployment preflight in S3/S6. No live feasibility claim or automatic procurement follows from S0. |
| F-09 | The local Git commit hook invokes `code-review-graph`, which raises `ModuleNotFoundError: No module named 'code_review_graph'`. The commit succeeds. | Observed local hook environment defect. Earlier MCP graph queries and independent reviews completed. | Report automatic indexing as unconfirmed. Repair the local CLI environment separately before relying on that hook. S0 does not install packages or weaken hooks. |

F-01 and F-02 affect necessary verification. Their remediation cannot be replaced with skips.
F-03 is a candidate retirement because its obsolete matcher conflicts with the documented current architecture.
No broad business repair is authorized in S0.
The ledger records requirement uncertainty as a deletion gate instead of treating absent evidence as obsolescence.

## Caller and language audit

The scan covered 1,610 tracked text files, including build files, hooks, workflows, source, tests and documentation.
Per-file reference entries distinguish workflow, build, hook, source, test, comment and documented mentions.
Matching a filename is navigation evidence. It does not establish that the line is executable or currently reached.
Repeated names such as `verdict.sh` produce explicit ambiguous-basename entries.

The top-level full runner imports nine helpers through a variable loop.
Their literal list and actual source statement were inspected together.
Test modules also bind helper paths to variables before sourcing them.
The focused-diagnostic fixture generates temporary runners with a helper import.
The JSON ledger records these examples rather than claiming literal matching resolves all Shell control flow.

Zero literal inbound references occurred for seven files:

- Query execution-fixture contract: necessary current fixture behavior. Preserve coverage rather than declare it unused.
- Critical-consumer Matching compatibility helper: merge into its canonical owner after caller review.
- Critical-consumer Matching compatibility test: remove alias after canonical test invocation replaces any operator use.
- Matching local-depth runner: retain native workload/integrity requirements through S5-E despite no literal caller.
- Kafka KRaft manifest contract: merge necessary semantics into S4-A.
- Matching E2E contract and PostgreSQL/Redis manifest contract also need semantic coverage before deletion.

The JSON ledger is the exact per-path source for reference counts.
Before implementation deletes a file, repeat scoped searches for its path, basename, exported functions, and dynamically constructed entry names.

The source graph reported no indirect impacted nodes for sampled Shell paths but also reported 44 unresolved call sites.
That result cannot establish no impact. Direct source/import and workflow review remains necessary.
No graph output alone authorizes deletion.

## Interface and validation handoff

The [resource and interface note](resources.md#task-names-and-argument-meanings) defines proposed property meanings.
The five proposed task names have no declarations in the inspected Gradle sources.
Dynamic task registry enumeration remains a focused S1 check, not an established runtime result.
`verifyAll` rejects focused scenario selection. CI requires disposable mode and rejects resource retention.
Explicit Docker context and kubeconfig belong to the run identity. Tests must not switch global user contexts.

Bounded S0 checks parsed all 170 Shell files successfully.
The Market Data verdict fixtures and critical verdict publication contract passed.
The archived Phase 6/7 shape check passed. F-01 and F-03 failed as recorded.
F-02 reproduced an incorrect success return. No bug fix occurred.
Offline resource rendering/calculation established declared budgets only.
No Java/native build, service integration suite, Docker deployment, or complete certification ran in S0.

Review document claims, inventory arithmetic, references, source hashes and unrelated-file preservation before delivery.
Do not restore the retired repository-wide Markdown link-checking suite.
The future implementation reviews use the same approved requirements and normal repository quality gates.

## Reproduce the cleanup finding without Docker

Run this from the repository root against the recorded baseline.
It sources the real function and replaces only the external Compose command and report sink.
The expected cleanup-failure result is nonzero. The observed process exit is zero with `reported_exit=0`.

```bash
bash -c '
  set -euo pipefail
  source scripts/lib/local-certification-framework.sh
  dry_run=false
  keep_resources=false
  compose_started=true
  namespace=""
  compose_failure() { return 17; }
  compose_command=(compose_failure)
  write_report() { printf "reported_exit=%s\n" "$1"; }
  cleanup
'
```

This isolates failure propagation. It does not reproduce a real daemon failure or measure live cleanup.

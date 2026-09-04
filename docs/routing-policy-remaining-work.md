# Phase 1 Trading Release Remaining-Work Inventory

This is the canonical implementation-status inventory for the complete Phase 1 Trading Release:
the daily Market Reference Artifact, Risk-to-Matching routing, deterministic Matching, downstream
durability, required read paths, deployment/security, certification, and pre-release cleanup. The
canonical release-scope definition lives in
[`system-boundaries.md`](../services/docs/architecture/system-boundaries.md#phase-1-trading-release-boundary).
Architecture documents describe the accepted target. This document alone distinguishes that target
from the repository's current implementation state.

Status was reconciled against the current worktree on 2026-09-04. An accepted design is not
`COMPLETED` until the repository contains its implementation and source-aligned local
production-like verification evidence. External production certification and live
staging/production promotion are not goals of this project. Their deployment values and run
sequence remain template work with placeholders and are neither current completion criteria nor
blockers. Aggregate issue [#10](https://github.com/WenHsuanYu/SimpleMatch/issues/10) and cleanup
issue [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119) were closed as `completed` on
2026-09-04 after the required Phase 1 child issues were completed and the fresh #119 gate passed.

The latest retained Query evidence is source-aligned to the certified pre-squash tree recorded in
`out/certification/issue-137-query-20260902-start-offset-query2/provenance.json`; the latest integrated
deployment evidence is source-aligned to `0d5f1737691dda104b5f5d878e0d61f644d0772a` in
`out/certification/issue-138-20260904-post-squash/evidence-manifest.json`. A fresh source-aligned
production-like run for the #119 cleanup was retained on 2026-09-04 at source revision
`93815beb11a8569bae5bcb10235a9d865ea9a27a`; its evidence and boundaries are recorded below. The
subsequent three-commit history-only squash preserved the exact validated runtime tree and was
pushed to `origin/master`. Retained evidence is therefore tree-aligned, while the existing
exact-commit-ID provenance helper remains a non-blocking workflow follow-up for future dependent
certifications.

## Status vocabulary

| Status | Meaning |
| --- | --- |
| `COMPLETED` | The required behavior and its source-aligned local production-like gate have passed; later non-certifying hardening is called out explicitly in the evidence. |
| `PARTIAL` | Required local implementation, deployment resources, or the local production-like gate is incomplete. |
| `NOT_STARTED` | No repository implementation of the target capability exists. |
| `OBSOLETE_TO_REMOVE` | Current code implements a superseded design and must be removed or migrated. |

## Verification boundary

The only completion target for this project is the repository-owned local production-like gate. It
uses local images and production-shaped dependency contracts, including the three-broker Matching
Kafka profile, 15 logical Matching owners, PostgreSQL, Redis, Debezium/Kafka Connect, Kubernetes
ownership, restart/replay, and end-to-end event evidence. A local pass is not a claim that the system
has been promoted externally, and this project does not require an external production
certification.

`deploy/k8s/overlays/local` is executable local configuration. The `staging` and `production`
overlays are deliberately separate templates: their registry names, image digests, external
endpoints, CIDRs, and credentials remain placeholders until a later promotion. Those placeholders
are prepared for a later promotion only; they do not keep a locally verified capability in `PARTIAL`
and must not be interpreted as a requirement to push images or obtain external credentials now.

## Interpreting `PARTIAL`

| Class | Current entries | Meaning |
| --- | --- | --- |
| Compatibility or legacy cleanup | None | The #119 source/configuration cutover and its fresh source-aligned local production-like gate are complete. |

## Non-blocking hardening and efficiency improvements

The following items were identified during certification review and are now implemented as
post-certification hardening. They must not replace the fresh, service-scoped Flyway Job, weaken the
proof that every certification run applies migrations against its own database state, or be used to
reuse a runtime namespace whose source or image identity changed.

| Improvement | Current implementation | Boundary |
| --- | --- | --- |
| Avoid repeated Gradle distribution downloads in one-shot migration Pods | The Flyway image prewarms the pinned wrapper distribution with a locked BuildKit cache, carries an immutable seed, and refreshes each Pod's writable cache on every invocation. The verifier image also uses a locked BuildKit Gradle cache (`53e990e`, `fe12230`). | This removes repeated distribution downloads without sharing mutable runtime state. Dependency resolution, migration execution, Job completion, and error evidence remain run-scoped. |
| Absorb a transient Matching Kafka coordinator race | The direct Kafka consumer retries only `RD_KAFKA_RESP_ERR_NOT_COORDINATOR`, for at most five attempts with `100/200/400/800 ms` backoff (`b2af17c`). | Non-retryable errors and exhausted retries remain fatal; no unknown recovery boundary is accepted and no certification phase is skipped. |
| Preserve evidence across a history-only squash | The pre-squash certification evidence is retained after verifying that the squash produced the exact same runtime tree. | Evidence is not relabeled as exact-HEAD provenance; the existing commit-ID check may require a future refinement before dependent evidence is reused. |

## Latest verification evidence (2026-09-04)

- The retained Query production-like run passed with status `PASSED` in namespace
  `simplematch-local-cert-20260901-180937-1202218`; its companion Query verdict is `PASS` with 9/9
  checks, including deterministic rebuild, PostgreSQL fallback, freshness restoration, Redis
  rebuild, active-processing liveness, and critical-path isolation. Reports are
  `out/certification/issue-137-query-20260902-start-offset-prod2/report.md` and
  `out/certification/issue-137-query-20260902-start-offset-query2/verdict.json`. The retained Query
  provenance records source revision `b6fa6d21854a8be3088a07459379f7f26336dd54`, the certified
  pre-squash tree.
- The latest integrated cross-service run passed all 62/62 phases in namespace
  `simplematch-local-cert-20260903-173801-1848616`. It includes the three retained CDC connectors,
  Flyway Jobs, Kubernetes workload/fleet checks, the CDC pause/recovery observation, zero-traffic
  freshness, sensitive-log checks, and retained-run provenance. Evidence is under
  `out/certification/issue-138-20260904-post-squash/`; its manifest records source revision
  `0d5f1737691dda104b5f5d878e0d61f644d0772a`.
- The fresh #119 source-aligned production-like run passed all 59/59 phases in namespace
  `simplematch-local-cert-20260904-114743-479272`, using the approved 2026-08-27 Market Reference
  delivery artifact and registry transport. It includes the v2-only runtime/deployment contract,
  seven service-scoped Flyway Jobs, Risk and Account outbox connector registration, the CDC
  pause/recovery and zero-traffic freshness observations, the 15-pod Matching fleet, and retained
  run provenance. Evidence is under
  `out/certification/issue-119-20260904-r2/`; its report is `PASSED` and its manifest records source
  revision `93815beb11a8569bae5bcb10235a9d865ea9a27a`.
- The current worktree's post-certification hardening passed the focused native Matching suite,
  the full 80-test/15-suite native ingress executable, Flyway shell contract/grammar checks, and
  Docker BuildKit Dockerfile checks. The fresh #119 run validates the pre-squash tree whose exact
  runtime contents were preserved by the subsequent history-only squash; the hardening remains
  non-blocking and does not replace the service-scoped migration or retained-provenance gates.

## Verification evidence history (through 2026-08-16)

- Native CTest now passes all 75 tests, including the pinned-writer startup gate, terminal alert,
  ownership fencing, bounded replay, commit watermark, and crash-window checks. The full messaging
  build also passes all 72 tests under ThreadSanitizer without a reported data race.
- A disposable single-node `simplematch-live` kind cluster ran one real `matching-0` process against
  an in-cluster Kafka broker. The process acquired and renewed its partition Lease, mounted a
  `ReadWriteOncePod` PVC, consumed the Open Barrier plus two order commands, reached `READY`,
  committed input offset 3, and published two `matching.events` records.
- A normal Pod restart demonstrated Lease handover from the old Pod UID to a new UID, PVC baseline
  replay, recovery to `READY`, zero Kafka lag, and no additional output events. This is an
  integration smoke only: the cluster had one node, one Kafka broker with replication factor 1,
  local-path storage, and no external production-platform certification. Those platform-specific
  controls are intentionally outside this project's acceptance boundary.
- The canonical three-worker `simplematch-live` local run now has fresh deployed evidence for both a
  normal `matching-0` Pod replacement and an exact Matching container crash. The normal replacement
  kept the same Node/PVC/PV and completed in 28.062 seconds with 3.225 seconds of marker-batch
  replay; the process-crash case kept the same Pod UID/Node/PVC/PV, moved restart count from 2 to 3,
  completed in 33.856 seconds with 3.161 seconds of replay, and both cases observed zero loss and
  zero duplicates. The strengthened deployed collector also passed the 15-pod, 5/5/5 placement,
  per-writer CPU cgroup, native-capacity, and per-event correlation gates. Evidence is under
  `out/certification/local-production-like/e2e-rebuild-20260815-144800/`. These are local
  marker-batch claims, not worker takeover, soak, full-day replay, or external production claims.
- The same canonical three-broker run also deleted only `kafka-0` normally and verified the
  two remaining brokers could complete an 8-command/8-event real data-plane batch with zero loss
  and duplicates while the replacement was not Ready. The replacement received a new Pod UID but
  returned to `simplematch-live-worker2` with the same `kafka-data-kafka-0` PVC and PV; after it
  became Ready, all 15 partitions of both Matching topics reported ISR `0,1,2`, the three KRaft
  voters were present, and maximum follower lag was zero. This proves local single-broker Pod-loss
  and same-PVC rejoin behavior, not worker loss, PVC loss, destructive storage takeover, or a
  full-day replay claim. Evidence is under
  `out/certification/matching-deployed/kafka-broker-replacement-20260815/`.
- A fresh canonical three-worker worker-stop run stopped
  `simplematch-live-worker2`, observed the Node become NotReady, restarted the same Docker
  container, and recovered `matching-0` on the same Pod UID, Node, PVC, and PV. Replacement took
  76.956 seconds and replay catch-up took 3.542 seconds; the run observed zero loss and zero
  duplicates. The deployed collector passed 15/15 readiness, 5/5/5 placement, all 15 writer
  CPU-allowance checks, native capacity, and the worker-stop E2E evidence. Evidence is under
  `out/certification/local-production-like/worker-stop-prep-20260816-r3/`. This proves only
  temporary same-worker recovery; it does not prove PVC loss, cross-node takeover, or external
  HA.
- The native local-day replay test processed the bounded 10,000-command envelope in bounded
  batches, and the repeated native profile passed 10 cycles / 102,000 commands and events with
  deterministic state and serialized-event checksums, zero loss, and zero duplicates. Evidence is
  under `out/certification/matching-local-depth/final-20260815/`. This is the side-project's
  bounded local-day and soak profile, not a 24-hour wall-clock endurance run.
- The Docker data move was corrected before the fresh 2026-08-15 run: Docker now uses a
  Linux-backed root, the repository preflight verified `simplematch-live` as one control-plane plus
  three labelled workers, and the canonical Kubernetes workload run completed. The earlier NTFS
  overlay failure remains documented as DT-012 for prevention; it is no longer the current
  environment result.
- Gateway Kubernetes resources were applied and inspected at API level: one replica, digest-pinned
  image, Bound data PVC, owner-0 Service, and resource-scoped ConfigMap RBAC. The placeholder
  Gateway image is not available, so its Pod remained `ErrImagePull`; no Gateway runtime or
  end-to-end admission claim follows from this check.
- The current implementation pass adds a bounded `marketdata-streamer` Kafka-to-gRPC runtime,
  authenticated Gateway operations over HTTP, a production Debezium Connect deployment template,
  projection replay reset operations, and a measured-versus-replay checksum in the native capacity
  benchmark. Focused service tests, the Matching benchmark smoke, connector contract checks,
  Kubernetes overlay validation, the complete Gradle test suite, global static analysis, and the
  native CMake/Ninja test suite pass.

- On 2026-08-12, the complete repository-owned local production-like gate passed with local
  `bootBuildImage`/Dockerfile images, the production-shaped Compose dependency graph, all seven
  Kubernetes Flyway Jobs, the Java/Matching workload fleet, and the Matching fleet verification.
  It used the explicitly approved `2026-08-11` Market Reference delivery fixture because a current
  `2026-08-12` fixture is not present; the report records that trading day. The gate's generated
  Compose project and Kubernetes namespace were removed after the run, including test PVCs/PVs.

- The certification runbook now separates the repository-owned local production-like gate from the
  later staging/production template sequence. The external sequence remains available as a
  promotion template, but it is not part of this project's acceptance target or a completion
  blocker.
- The Kafka profile validator now accepts an external TLS/SASL command-properties file and rejects
  duplicate replica broker identities. These changes strengthen the repository gate; they do not
  claim certification of an externally operated production environment.
- The Matching Kafka profile tests now fail closed for partition-level and topic-wide replica/ISR
  drift, leader loss, unsafe broker policy, unsafe producer settings, insufficient 30-day capacity,
  and the one-broker local profile. The local certification runner passes producer and capacity
  evidence into the same validator.
- The Account v2 source/configuration cutover guard and the repository-local
  `AccountReservationSagaRecoveryIntegrationTest` pass. The test uses real Account v2 gRPC and
  independent H2 transactions to prove remote success followed by Risk failure recovers the same
  reservation without a second Account mutation.
- The first same-day gate attempt failed closed at `kubernetes-inputs` because the default current
  day had no approved delivery manifest. That was an input-fixture blocker, not a Kubernetes
  workload failure; rerunning with the repository's approved historical fixture passed. No external
  Kubernetes context, broker credentials, PostgreSQL endpoint, or external FIX session is required
  for this local milestone.

## Frozen target boundaries

- The Phase 1 Trading Release is the first complete pre-release trading-system boundary. It is not
  the same concept as a numbered refactor phase.
- Market Reference is an offline builder, not a runtime service. It produces one immutable
  `market_reference.json` for each Asia/Taipei trading day.
- The artifact covers every Phase 1 eligible XTAI and ROCO regular-board common stock. It contains
  metadata, reusable market rules, instrument facts, eligibility, and complete stable routing
  assignments.
- Risk and all 15 Matching pods load the same artifact at startup. There is no Market Reference
  runtime topic, outbox, projection, or synchronous lookup.
- `matching.commands` and `matching.events` each have 15 fixed partitions. `matching-N` owns
  partition `N`, and each partition owns at most 150 instrument order books.
- Kafka `matching.commands` is the authoritative durable ordered input journal. A separate local
  per-command fsync journal on a file or PVC is not part of the target architecture; the small PVC
  baseline is only a recovery-coordinate acceleration index and Kafka remains authoritative.
- Each native Matching process uses a Kafka ingress thread, a preallocated input ring, one
  single-writer Matching core, a preallocated output ring, and a Kafka publisher/coordinator.
- PostgreSQL is the permanent trade and projection store, but it is not used to recover Matching
  order books.
- The required Query capability owns rebuildable PostgreSQL and Redis projections and never reads a
  different service's database. Query failure degrades reads but cannot pause the trading path.
- Risk and Account now use one final typed v2 reservation RPC; Account v1 transport is removed.
- One QuickFIX Gateway owns Phase 1 FIX sessions. It starts `PRE_OPEN`; admission opens only after
  the accepted readiness checks pass.
- Every Phase 1 workload passes the accepted local production-like overlay, Secret,
  transport-security, migration-job, connector, network-policy, readiness, telemetry, and
  deployment gates. Staging/production apply and environment-owned enforcement remain promotion
  templates rather than this project's certification target.
- Kafka delivery is at least once. Deterministic identities plus consumer-owned inboxes make local
  business effects idempotent.

## Summary

| Capability | Current status | Primary tracker |
| --- | --- | --- |
| Offline official-source acquisition and normalization | `COMPLETED` | [#121](https://github.com/WenHsuanYu/SimpleMatch/issues/121) |
| Candidate/final artifact workflow and approval evidence | `COMPLETED` | [#124](https://github.com/WenHsuanYu/SimpleMatch/issues/124) |
| Canonical artifact schema, identity, and packaging | `COMPLETED` | [#122](https://github.com/WenHsuanYu/SimpleMatch/issues/122) |
| Stable 15-partition routing assignment | `COMPLETED` | [#123](https://github.com/WenHsuanYu/SimpleMatch/issues/123) |
| Runtime Market Reference publication stack (retired) | `COMPLETED` | [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119) |
| Risk artifact loading and `matching.commands` publication | `COMPLETED` | [#126](https://github.com/WenHsuanYu/SimpleMatch/issues/126) |
| Native deterministic Matching runtime | `COMPLETED` | [#127](https://github.com/WenHsuanYu/SimpleMatch/issues/127) |
| Kafka journal recovery and trading-day barriers | `COMPLETED` | [#128](https://github.com/WenHsuanYu/SimpleMatch/issues/128) |
| `matching.events` wire identity and publication | `COMPLETED` | [#129](https://github.com/WenHsuanYu/SimpleMatch/issues/129) |
| Permanent PostgreSQL trades and fills | `COMPLETED` | [#130](https://github.com/WenHsuanYu/SimpleMatch/issues/130) |
| Account critical Matching-event consumption | `COMPLETED` | [#131](https://github.com/WenHsuanYu/SimpleMatch/issues/131) |
| Final Account reservation v2 RPC | `COMPLETED` | [#139](https://github.com/WenHsuanYu/SimpleMatch/issues/139) |
| Account DataSource Boot auto-configuration | `COMPLETED` | [#140](https://github.com/WenHsuanYu/SimpleMatch/issues/140) |
| Durable QuickFIX execution delivery | `COMPLETED` | [#132](https://github.com/WenHsuanYu/SimpleMatch/issues/132) |
| Runtime market-data projection | `COMPLETED` | [#133](https://github.com/WenHsuanYu/SimpleMatch/issues/133) |
| Required query service and Redis read models | `COMPLETED` | [#137](https://github.com/WenHsuanYu/SimpleMatch/issues/137) |
| Gateway operational admission control | `COMPLETED` | [#135](https://github.com/WenHsuanYu/SimpleMatch/issues/135) |
| Matching StatefulSet ownership and fencing | `COMPLETED` | [#134](https://github.com/WenHsuanYu/SimpleMatch/issues/134) |
| Cross-service deployment, security, and observability | `COMPLETED` | [#138](https://github.com/WenHsuanYu/SimpleMatch/issues/138) |
| Production-shaped Kafka topic profile | `COMPLETED` | [#125](https://github.com/WenHsuanYu/SimpleMatch/issues/125) |
| Performance and recovery certification | `COMPLETED` | [#136](https://github.com/WenHsuanYu/SimpleMatch/issues/136) |
| Pre-release compatibility and legacy cleanup | `COMPLETED` | [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119) (with completed [#120](https://github.com/WenHsuanYu/SimpleMatch/issues/120)) |

### Local resilience dependency issues #154 and #155

The local PostgreSQL/Redis and Kafka KRaft manifests are implemented and their static/fake
lifecycle contracts pass. The focused seam is
`scripts/run-local-resilience-dependencies.sh`; it accepts only an existing disposable namespace,
captures exact runtime identity, and writes diagnostic-only evidence without rerunning the complete
production-like runner. PostgreSQL evidence requires the original slot-0 Pod, RWO PVC/PV, and a
durable row in Flyway-owned `risk_service.local_resilience_marker` after worker return; it must not
write observer-owned `risk_service.cdc_delivery_lag`. Kafka evidence requires all three fixed ordinal identities,
RF3 marker data, two available brokers during one worker fault, and ISR3 after rejoin. Redis evidence
only requires portable singleton readiness and records that `emptyDir` cache state is disposable.

| Issue | Current status | Remaining completion gate |
| --- | --- | --- |
| [#154](https://github.com/WenHsuanYu/SimpleMatch/issues/154) PostgreSQL and Redis in Kubernetes | `PARTIAL` | Run the focused PostgreSQL and Redis diagnostics in a fresh run-owned namespace and retain valid reports; parent #151 must still consume them in its aggregate baseline/fault-family evidence. |
| [#155](https://github.com/WenHsuanYu/SimpleMatch/issues/155) Durable Kafka KRaft cluster | `PARTIAL` | Run the focused Kafka worker-loss diagnostic and retain valid RF3/identity/PVC/rejoin evidence; parent #151 must still consume it in its aggregate baseline/fault-family evidence. |

These reports do not close #151 by themselves and do not claim cross-node storage takeover,
production HA, or external certification. The later #162–#167 issues own full-local orchestration,
worker-stop coverage across every workload family, and the final aggregate verdict.

## Detailed inventory

### MR-1: Acquire and normalize official market facts

- **Current status:** `COMPLETED`
- **Target behavior:** An offline repository tool fetches official TWSE and TPEx company,
  instrument, calendar, reference-price, and price-limit data. It selects all Phase 1 eligible XTAI
  and ROCO regular-board common stocks and records explicit reasons for known but unsupported
  instruments. Yahoo Finance is not an authoritative source.
- **Current evidence:** `tools:market-reference-builder` fetches or reads captured official source
  documents, records source provenance/checksums, normalizes XTAI/ROCO facts, and fails closed on
  malformed, duplicate, incomplete, stale, or inconsistent records. Deterministic fixtures and
  endpoint contracts cover all five required official documents.
- **Missing behavior:** None for this capability. Runtime use of the produced artifact belongs to
  #126 and #127.
- **Acceptance criteria:** Deterministic fixtures and live-source contract tests cover TWSE company
  data, TPEx company data, TWSE daily reference/limit prices, TPEx next-day reference/limit prices,
  and the official trading calendar. Every eligible instrument has complete identity, venue, lot,
  tick, reference, lower-limit, and upper-limit facts.
- **Blocking dependencies:** None.
- **GitHub issue:** [#121](https://github.com/WenHsuanYu/SimpleMatch/issues/121).

### MR-2: Build preliminary and final daily artifacts

- **Current status:** `COMPLETED`
- **Target behavior:** D-1 produces a preliminary candidate containing the instrument universe,
  eligibility, and stable routing. On trading-day morning the builder re-fetches every official
  source, re-reconciles the universe, adds the official reference and limit prices, and produces the
  only final artifact that may open the market.
- **Current evidence:** The builder has `candidate` and `final` CLI commands, captures bounded
  review/diff evidence, requires `--approved-by` for finalization, verifies exact final bytes, and
  refuses to overwrite an approved trading-day directory.
- **Missing behavior:** None for this offline build/approval capability. Applying a final delivery
  fragment and admitting orders remain #126, #127, #135, and #138 work.
- **Acceptance criteria:** Approval reviews summary counts, additions/removals, eligibility changes,
  route changes, source checksums, validation results, artifact size, delivery form, and
  `contentSha256`; it does not require manual inspection of every instrument row.
- **Blocking dependencies:** None.
- **GitHub issue:** [#124](https://github.com/WenHsuanYu/SimpleMatch/issues/124).

### MR-3: Define artifact schema, identity, retention, and delivery

- **Current status:** `COMPLETED`
- **Target behavior:** One JSON envelope contains `metadata`, `marketRules`, `marketSnapshot`, and
  `routingPolicy`. Reusable tick tables are normalized at the top level. Instrument facts do not
  duplicate their routing partition.
- **Current evidence:** `shared-java:market-reference-contract` supplies the canonical envelope,
  codec, external checksum, structural validator, and startup validator. The builder retains
  approved output and emits an immutable ConfigMap or digest-pinned OCI data-image contract; the
  shared fixture is verified by both Java and native C++ loaders.
- **Missing behavior:** Runtime mounting and readiness wiring are #126/#127 integration work.
- **Acceptance criteria:** Artifact identity is `tradingDay + contentSha256`. The checksum is not
  embedded in the JSON; it is supplied externally. Every eligible instrument has exactly one route,
  every unsupported instrument has none, and the declared partition count is 15. Approved output
  is retained under `config/market-reference/approved/YYYY-MM-DD/`. Artifacts up to 900 KiB use an
  immutable ConfigMap; larger artifacts use a digest-pinned OCI data image and init container. Both
  mount `/etc/simplematch/market-reference/market_reference.json`.
- **Blocking dependencies:** None.
- **GitHub issue:** [#122](https://github.com/WenHsuanYu/SimpleMatch/issues/122).

### MR-4: Assign stable routes within fixed capacity

- **Current status:** `COMPLETED`
- **Target behavior:** Exactly 15 partitions exist, each with capacity for 150 instrument order
  books. Existing eligible instruments keep their previous partition; removals disappear; new
  instruments go to the least-loaded partition with the lowest partition ID breaking ties. The
  initial baseline sorts by `(venueMic, symbol)` before applying the same least-loaded rule.
- **Current evidence:** `StableRoutingAllocator` implements deterministic baseline allocation,
  prior-route retention, capacity diagnostics, 15-by-150 enforcement, and bounded route diffs;
  fixtures prove deterministic rebuild and incremental behavior.
- **Missing behavior:** None.
- **Acceptance criteria:** Rebuilding from identical inputs is byte-identical; adding one instrument
  does not move existing eligible instruments; no partition exceeds 150; more than 2,250 eligible
  instruments fails the build.
- **Blocking dependencies:** None.
- **GitHub issue:** [#123](https://github.com/WenHsuanYu/SimpleMatch/issues/123).

### MR-5: Remove the runtime Market Reference stack

- **Current status:** `COMPLETED`
- **Target behavior:** No runtime Market Reference process, PostgreSQL snapshot/routing tables,
  outbox, Debezium connector, Kafka topic, Risk projection consumer, or Matching routing-policy
  ingress remains.
- **Current evidence:** The offline builder remains the artifact authority. The former publisher
  module, Flyway jobs/migrations, Debezium connector, Kubernetes runtime/configuration, Risk
  routing projection/resolver, native policy ingress, and obsolete routing-policy protobuf were
  removed. Risk and Matching use the shared startup artifact identity and final command envelope.
- **Missing behavior:** None for the repository-owned project target. The fresh #119 run passed the
  rendered Kubernetes, migration, workload, connector, CDC, fleet, and retained-provenance gates.
- **Acceptance criteria:** Repository search finds no runtime publication or consumption of
  `market-reference.snapshots` or `market-reference.routing-policies`; Risk and Matching readiness
  prove the mounted artifact identity instead.
- **Blocking dependencies:** None. MR-1 through MR-4, RM-1, and ME-1 are complete, and the fresh
  source-aligned local production-like verification passed.
- **GitHub issue:** [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119), section B; its
  native blockers are the replacement issues above.

### RM-1: Load the artifact in Risk and publish Matching commands

- **Current status:** `COMPLETED`
- **Target behavior:** Risk loads and validates the final artifact once at startup, resolves each
  eligible instrument to its explicit partition, persists the artifact identity and partition with
  Admission, and publishes `MatchingCommand` records to `matching.commands` through its outbox.
- **Current evidence:** `DailyMarketReferenceArtifactLoader` validates the mounted daily artifact at
  Risk startup; `DailyArtifactAdmissionRoutingResolver` persists its identity and explicit route;
  `MatchingBarrierOutboxFactory` writes Open/Close barriers to all 15 partitions; and focused
  resolver, barrier, transaction, Flyway, and application-context tests pass. The repository-local
  PostgreSQL/Kafka Connect/Kafka CDC contract also passes, including connector pause/resume and
  exact record delivery. A separate native fixture has verified the local `matching.commands` to
  `matching.events` broker path. The local Kubernetes overlay now contains two in-cluster Debezium
  workers, and the certification runner registers the Risk and Account outbox connectors only
  after Flyway completes, then requires both connectors and their tasks to report `RUNNING`. The
  retained source-aligned production-like runs completed the Risk
  connector, Kafka profile, Kubernetes workload, and retained-provenance phases.
- **Missing behavior:** None for the repository-owned project target. The offline builder and
  production artifact approval workflow remain MR-1 through MR-4 work rather than being supplied by
  Risk. External deployment values remain promotion-template work.
- **Acceptance criteria:** New order, cancel, `TRADING_DAY_OPEN_BARRIER`, and
  `TRADING_DAY_CLOSE_BARRIER` records target explicit partitions 0-14. Recovery never recomputes an
  admitted route. No command is published for a stale or mismatched artifact.
- **Blocking dependencies:** MR-3, MR-4, and KC-1.
- **GitHub issue:** [#126](https://github.com/WenHsuanYu/SimpleMatch/issues/126).

### ME-1: Build the native single-writer Matching runtime

- **Current status:** `COMPLETED`
- **Target behavior:** Each native `matching-N` contains a Kafka ingress thread, preallocated SPSC
  input ring, one CPU-pinned single-writer core owning at most 150 order books, preallocated SPSC
  output ring, and Kafka publisher/offset coordinator. The core performs no network or disk I/O,
  locks, or post-warmup allocation.
- **Current evidence:** The native runtime has preallocated SPSC ingress/output rings, a
  single-writer price-time order-book core capped at 150 instruments. The rings now use
  cache-line-isolated monotonic producer/consumer sequences, power-of-two typed storage,
  acquire/release publication, bounded batch consumption, and explicit full-capacity
  backpressure. The writer reserves the mathematical worst-case event burst plus one
  `EndOfInput` marker before changing core state; output indices and the terminal count are checked
  before publication coordination. The runtime also has command decoding, direct
  partition assignment, output backpressure, a librdkafka adapter, lifecycle executable/probes, and
  deterministic CTest coverage, including a bounded-capacity benchmark smoke and a Close Barrier
  regression that covers both sides of an order book, plus explicit input-ring, output-ring, and
  order-book-capacity checks. The capacity report now compares native state checksums and
  deterministic serialized event bytes, and also records the benchmark process's effective CPU
  affinity when pinning is requested; without an explicit CPU set, the deployed binary pins the
  writer to the first CPU in its effective cgroup cpuset. A `MatchingRuntimeSupervisor` now starts
  the writer parked, validates the recovery boundary before release, propagates terminal failures
  out of band, observes ownership independently, and bounds shutdown draining. The production
  binary uses the supervisor for Kafka ingress, the pinned writer, asynchronous publication
  coordination, and contiguous commit handoff. CTests cover the startup gate, affinity failure,
  terminal alert, and threaded driver path.
  A local broker smoke and a disposable kind smoke have consumed real `matching.commands` records
  and published acknowledged `matching.events` records.
- **Missing behavior:** None for the repository-owned local target. The deployed evidence does not
  certify external hardware or a production cluster, which are not required by this project. Those
  runtime adapters must not enter the Matching core hot path.
- **Acceptance criteria:** The same ordered command stream and pinned binary produce identical state
  checksums and event bytes. Ring exhaustion never overwrites, drops, or expands heap storage.
  Output backpressure stalls safely and drives the accepted admission policy.
- **Blocking dependencies:** MR-3, RM-1, and KC-1. ME-2 builds on this capability rather than
  forming a circular prerequisite.
- **GitHub issue:** [#127](https://github.com/WenHsuanYu/SimpleMatch/issues/127).

### ME-2: Recover from Kafka and enforce trading-day barriers

- **Current status:** `COMPLETED`
- **Target behavior:** `matching.commands` is the authoritative replicated input journal. An Open
  Barrier defines the daily replay baseline; a Close Barrier expires ROD orders and closes the
  partition deterministically. PVC metadata is an acceleration index, not the authority.
- **Current evidence:** `PartitionReplayCoordinator` models explicit partition assignment,
  Open/Close barriers, command de-duplication, retained-record replay, output ACK tracking, and a
  contiguous commit watermark. A bounded `InputOffsetLedger` now maps every accepted process-local
  `InputSequence` (including deduplicated inputs) to its Kafka offset, accepts out-of-order
  completion without crossing an incomplete prefix, and releases only completed contiguous entries.
  CTests cover the ledger, coordinator handoff, replay, and barrier invariants. The native
  librdkafka adapter now exposes bounded retained batches, committed/end offsets, seeking, and
  synchronous commits; the runtime replays the PVC baseline (or scans for a retained Open Barrier)
  in bounded batches through the pinned writer before live polling. Async delivery reports map back
  to publication IDs, retain unresolved output until a terminal delivery result, retry ambiguous
  results before admitting later input, and never commit before the output ACK. Tests cover commit
  acknowledgement loss, ambiguous delivery retry, bounded replay, and graceful shutdown behavior.
- **Completed evidence and limits:** The canonical three-broker local run exercises a real deployed
  process crash, normal Matching Pod replacement, single-broker loss with data-plane continuity,
  same-PVC ISR rejoin, and one kind worker stop with same-worker recovery. The worker-stop run also
  passed per-event correlation, zero-loss/duplicate checks, bounded replacement/replay limits, and
  same Pod UID/Node/PVC/PV continuity. Native tests cover the bounded local-day retained replay,
  output ACK and contiguous commit rules, crash windows, shutdown, and barrier fail-closed behavior.
  PVC loss, destructive storage takeover, and cross-node takeover remain outside this toy project's
  local completion contract. External production certification remains outside the project boundary.
- **Acceptance criteria:** Outputs are ACKed before the input offset becomes completed; commits
  never cross a gap. Crash windows may replay identical events but cannot lose an accepted command.
  A missing retained Open Barrier fails closed. No periodic order-book snapshot is added unless the
  recovery certification misses its SLO.
- **Blocking dependencies:** ME-1 and KC-1.
- **GitHub issue:** [#128](https://github.com/WenHsuanYu/SimpleMatch/issues/128).

### ME-3: Publish deterministic Matching Events

- **Current status:** `COMPLETED`
- **Target behavior:** `matching.events` carries `ORDER_RESTED`, `TRADE_EXECUTED`,
  `ORDER_CANCELLED`, and `ORDER_EXPIRED`. One trade event describes both maker and taker legs.
- **Current evidence:** `matching_runtime_v1.proto`, the native event encoder, deterministic
  event/trade identity, output/match indices, raw-byte hash fixtures, and Java envelope parsing are
  implemented and covered by native and shared-contract tests. The native idempotent producer now
  checks delivery callbacks, and a local broker smoke has verified acknowledged event publication
  and the deterministic record key; the disposable kind smoke observed two published event keys and
  retained that count across a normal Matching restart. The retained source-aligned production-like
  runs also completed the three-broker topic, image-lock, workload, and fleet gates.
- **Missing behavior:** None for the repository-owned project target. External broker operation and
  production promotion remain outside the project's acceptance boundary.
- **Acceptance criteria:** `eventId` derives from identity version, trading session, partition,
  command, and output index; `tradeId` uses command and match index. Event type is not part of
  `eventId`. Consumers hash the exact Kafka record value bytes. Same ID/same hash is a duplicate;
  same ID/different hash is quarantined as a deterministic violation. C++ golden bytes parse in
  every Java critical consumer.
- **Blocking dependencies:** ME-1 and KC-1.
- **GitHub issue:** [#129](https://github.com/WenHsuanYu/SimpleMatch/issues/129).

### PS-1: Permanently store trades and order-fill legs

- **Current status:** `COMPLETED`
- **Target behavior:** Persistence consumes every `matching.events` partition and atomically stores
  inbox identity/hash, one immutable trade, maker/taker order-fill legs, and order projections.
- **Current evidence:** Flyway V3 creates a raw-hash inbox, immutable `trades` and `order_fills`,
  projections, progress, and quarantine. The critical consumer applies a final Matching Event in one
  transaction and commits its Kafka acknowledgement only afterward; focused store, consumer, and
  migration tests pass. The retained critical-consumer production-like evidence and integrated
  cross-service run cover the deployed Persistence path.
- **Missing behavior:** None for the repository-owned project target. External production deployment
  evidence is a future promotion concern, not a project blocker.
- **Acceptance criteria:** DB commit precedes Kafka offset commit. IDs use 32-byte binary columns
  with exact-length checks; quantities are `BIGINT` shares; prices are `BIGINT` in 1/10,000 TWD;
  trading day is `DATE`; partition is constrained to 0-14. PostgreSQL outage is buffered by Kafka
  and never blocks the Matching hot path directly.
- **Blocking dependencies:** ME-3.
- **GitHub issue:** [#130](https://github.com/WenHsuanYu/SimpleMatch/issues/130).

### AC-1: Apply Matching Events to Account Authority

- **Current status:** `COMPLETED`
- **Target behavior:** Account consumes `matching.events` as a critical consumer and applies both
  sides' fills or terminal releases exactly once in local transactions.
- **Current evidence:** Flyway V7, the final-event account application service, durable inbox,
  payload hash validation, maker/taker fill mapping, quarantine, and manual acknowledgement are
  implemented and covered by focused and application-context tests. The retained critical-consumer
  production-like evidence and integrated cross-service run cover Account's deployed delivery path;
  the independent Account reservation-RPC cutover is tracked separately by AR-1.
- **Missing behavior:** None for the repository-owned project target. External production
  certification is not required.
- **Acceptance criteria:** Inbox claim, payload-hash validation, account/reservation mutation,
  lifecycle outbox, and inbox completion commit atomically. A failed record never lets a later
  record overtake it.
- **Blocking dependencies:** ME-3.
- **GitHub issue:** [#131](https://github.com/WenHsuanYu/SimpleMatch/issues/131).

### AR-1: Migrate Account reservation RPC to the final v2 contract

- **Current status:** `COMPLETED`
- **Target behavior:** Risk and Account use one typed v2 reservation boundary for the durable
  Admission saga. The RPC carries accepted identities, whole-share quantity, fixed-point monetary
  values, reservation terms, and typed outcomes without legacy string parsing in domain behavior.
- **Current evidence:** The typed v2 Protobuf contract carries the reservation identity, venue-qualified
  instrument, side, whole-share quantity, fixed-point price/notional, and lifecycle outcome. Account
  exposes a v2 server adapter over the existing Account Authority, persists the venue MIC, and
  includes it in retry equivalence. Risk's production reservation client uses the v2 stub with a
  bounded deadline and preserves validation, conflict, unavailable, and internal Account failures.
  Equivalent retries replay one outcome and conflicting request reuse maps to a typed conflict. The
  Account adapter validates the shared v2 metadata envelope before persistence, and the repository
  caller guard proves non-Account production services do not construct the v1 RPC client. Focused
  contract, Account transaction, Risk gRPC-boundary, Risk identity, and repository-local saga
  recovery tests pass.
- **Missing behavior:** None for the repository-owned project target. External production deployment
  proof and staging/production configuration are promotion-template work.
- **Acceptance criteria:** The Account transaction remains service-owned and no Risk transaction is
  held across the RPC. Equivalent retries preserve one reservation outcome; conflicting retries are
  typed conflicts; remote success followed by Risk failure recovers without reserving twice.
- **Blocking dependencies:** None; the typed Account Authority and durable Admission foundations
  already exist.
- **GitHub issue:** [#139](https://github.com/WenHsuanYu/SimpleMatch/issues/139).

### FG-1: Deliver Matching Events durably over FIX

- **Current status:** `COMPLETED`
- **Target behavior:** The single QuickFIX Gateway consumes `matching.events` critically, stores a
  durable event inbox and per-order delivery ledger, and emits stable trade, rest, cancel, expiry,
  IOC, and FOK lifecycle reports through a JDBC-backed QuickFIX message store.
- **Current evidence:** Gateway Flyway V1 now creates a durable inbox, exact raw hash evidence,
  delivery ledger, progress, quarantine, and JDBC QuickFIX/J message-store tables. The final-event
  consumer uses strict retry/quarantine, deterministic delivery/Exec identities, and commits only
  after delivery intents persist; focused tests and QuickFIX certification tests pass. The retained
  critical-consumer production-like evidence and integrated cross-service run cover durable Gateway
  delivery and recovery boundaries.
- **Missing behavior:** None for the repository-owned project target. Socket delivery deliberately
  remains at-least-once; an externally operated production session is not required for this project.
- **Acceptance criteria:** Kafka offset commits only after all required delivery intents are
  durable. Socket delivery is at least once; retransmission preserves FIX session semantics and
  stable `ExecID`. Critical lifecycle reports cannot be skipped to an ordinary DLQ.
- **Blocking dependencies:** ME-3. GO-1 composes this consumer's status after durable delivery
  exists rather than forming a circular prerequisite.
- **GitHub issue:** [#132](https://github.com/WenHsuanYu/SimpleMatch/issues/132).

### MD-1: Build the non-critical market-data projection

- **Current status:** `COMPLETED`
- **Target behavior:** A separate runtime projection consumes `matching.events` and builds
  rebuildable last-trade and top-five order-book views. It is not the offline Market Reference
  builder.
- **Current evidence:** `services/market-data-projection` owns a Flyway projection/inbox/outbox,
  ordered final-event consumer, complete top-five/last-trade snapshot encoder, delayed retry/DLQ,
  rebuild service, `marketdata.events` publisher, and Redis cache repair path. The new
  `marketdata-streamer` consumes complete snapshots from that topic and exposes a bounded public
  gRPC subscription with venue-qualified and symbol-only filters. A protected projection replay
  reset endpoint and Kubernetes base/overlay resources are now present. Focused projection and
  streamer tests pass. The repository-local Compose environment includes Redis with AOF persistence,
  and the production profile enables the projection and Redis settings. The retained local
  production-like run for revision `10ba747c1ce8abd474468cd1b77042ebd1eaf505` and projection image
  digest `sha256:f537b22e2ea35302230ecd4e5a84279cd0ff297e7df409880e409a029d7e624c`
  passed deterministic replay through the public gRPC subscription, PostgreSQL durability during
  Redis outage, Redis repair, restoration, 15-partition Matching isolation, and critical-consumer
  state isolation.
- **Intentionally excluded:** The authorized private notification stream remains a separate
  compatibility boundary; only the public snapshot stream is in this entry. The local result does
  not claim external production promotion or exactly-once network delivery.
- **Acceptance criteria:** Projection failure does not affect Matching, permanent trade storage,
  Account, QuickFIX, or admission. Delayed retry/DLQ is allowed because the view can be rebuilt.
- **Blocking dependencies:** ME-3.
- **GitHub issue:** [#133](https://github.com/WenHsuanYu/SimpleMatch/issues/133).

### QS-1: Build the required Query capability and Redis read models

- **Current status:** `COMPLETED`
- **Target behavior:** A required Phase 1 `query-service` exposes read-only order, execution,
  account-summary, and active-market-reference views from query-owned PostgreSQL and Redis
  projections. It is non-critical to trading admission but not optional for release completion.
- **Current evidence:** `services/query-service` now provides the separate Spring service, Flyway
  inbox/checkpoint/read-model schema, asynchronous final Matching and Account lifecycle consumers,
  versioned read APIs, active-artifact installation seam, freshness metadata, replay reset, and
  optional Redis read-through fallback. Cache read and write failures fall back to the durable
  PostgreSQL projection. Focused H2 projection and cache-fallback tests pass. The current source
  revision also passes the query-service, certification, Kafka, Kubernetes, and critical-consumer
  contract suites. The retained production-like run passed in namespace
  `simplematch-local-cert-20260901-180937-1202218`; its companion Query verdict is `PASS` with 9/9
  checks for deterministic rebuild, PostgreSQL fallback, freshness restoration, Redis rebuild,
  active-processing liveness, and critical-path isolation. Earlier Desktop attempts that stopped
  before deployment remain historical diagnostics, not current blockers.
- **Missing behavior:** None for the repository-owned project target. The service-context test also
  proves the shared canonical-DSN/pool adapter and no competing `spring.datasource.*` source.
  External production certification is not a prerequisite.
- **Acceptance criteria:** Query never reads another service's database or scans Kafka synchronously.
  Redis can be deleted and rebuilt; misses/outages fall back to PostgreSQL; responses disclose
  freshness; and Query failure cannot pause any critical trading component.
- **Blocking dependencies:** MR-3, ME-3, and AC-1.
- **GitHub issue:** [#137](https://github.com/WenHsuanYu/SimpleMatch/issues/137).

### GO-1: Operate one Gateway admission authority

- **Current status:** `COMPLETED`
- **Target behavior:** One Gateway starts `PRE_OPEN` and exposes `status`, `open`,
  `pause-new-orders`, `interrupt-market`, and `close-day`. It automatically closes at session end
  and automatically pauses new orders when critical readiness becomes unsafe.
- **Current evidence:** The Gateway now starts `PRE_OPEN` with the five accepted admission states;
  it keeps cancellation available only during `NEW_ORDERS_PAUSED`. A pure
  `TradingSystemStatusEvaluator` verifies 15 owners, identities, recovery/lag, quarantine, Kafka
  topology, stale status, and critical-consumer age. The controller requires three fresh ready
  observations to open, auto-pauses/interrupts, auto-closes in Asia/Taipei time, never auto-reopens,
  records operations in Flyway V2, and exposes a fixed five-command application boundary. Focused
  state-machine, controller, audit, ingress, migration, and application-context tests pass. The
  retained integrated production-like run completed the Gateway, workload, health, metrics, and
  retained-provenance phases.
- **Missing behavior:** None for the repository-owned project target. The authenticated HTTP adapter
  remains disabled by default and does not invent live facts; external production certification is
  outside the project target.
- **Acceptance criteria:** `open` verifies Risk, 15 Matching owners, identical day/artifact/schema/
  algorithm versions, recovery lag zero for three checks, no quarantine, and critical-consumer
  readiness. Status silence over five seconds pauses new orders. Oldest unprocessed critical event
  warns at 30 seconds and pauses at 120 seconds. Identity or artifact inconsistency interrupts the
  market. Recovery never auto-reopens. Zero market activity remains Ready.
- **Blocking dependencies:** RM-1, ME-2, PS-1, AC-1, FG-1, and KD-1.
- **GitHub issue:** [#135](https://github.com/WenHsuanYu/SimpleMatch/issues/135).

### KD-1: Deploy and fence the fixed Matching fleet

- **Current status:** `COMPLETED`
- **Target behavior:** A 15-replica StatefulSet maps pod ordinal directly to partition. Each pod has
  a `ReadWriteOncePod` PVC and a per-partition Kubernetes Lease, and receives the artifact through
  the accepted ConfigMap or OCI path.
- **Current evidence:** `LeaseFencedPartitionOwnershipPermit` blocks native assign, replay, match,
  output, and commit without a confirmed permit, and self-fences after five seconds of lease
  uncertainty. A 15-replica StatefulSet, headless Service, `ReadWriteOncePod` PVCs, per-partition
  Lease RBAC/resources, OCI-data-image patch, PDB, CPU/affinity policy, native Kubernetes Lease and
  Kafka adapters, executable readiness/liveness probes, manifest tests, and recovery runbook are
  present. A fresh single-node local kind run started all 15 Matching Pods with their individual
  Lease holders and `ReadWriteOncePod` PVCs after valid per-partition Open Barriers were published.
- **Missing behavior:** None for the repository-owned project target. The local 15-owner run uses
  the documented 2 GiB local resource override and a single-node, local-image profile, and the
  complete local gate verifies the fleet, Lease ownership, RWOP PVCs, barriers, readiness, and
  rollout. Production CSI behavior, CPU Manager static policy, production-shaped resource capacity,
  and an externally operated target cluster remain template-only promotion evidence.
- **Acceptance criteria:** `matching-N` cannot poll, replay, match, publish, or become Ready without
  its partition permit. Lease uncertainty for five seconds self-fences the runtime. Replacement
  waits for storage and Lease ownership, replays, and reaches Ready before operator reopen. The
  production overlay retains the requested three dedicated CPUs and CPU-manager/static-policy
  settings as a later promotion template; external CPU-manager certification is not a project
  acceptance criterion.
- **Blocking dependencies:** ME-1 and ME-2.
- **GitHub issue:** [#134](https://github.com/WenHsuanYu/SimpleMatch/issues/134).

### PD-1: Harden the cross-service deployment and security baseline

- **Current status:** `COMPLETED`
- **Target behavior:** Every Phase 1 Java workload and retained connector uses reusable Kubernetes
  bases/overlays, service-owned migration and CDC jobs, authenticated encrypted transport,
  least-privilege policy, business-role readiness, structured safe application logs, basic
  health/metrics endpoints, and the key delivery/recovery metrics needed by the local lab.
  Matching-specific ownership and fencing remain in KD-1.
- **Current evidence:** `deploy/k8s/base` and local/test/staging/production overlays now cover the
  Java services, retained QuickFIX/Matching resources, service ConfigMaps/RBAC, one-shot service-
  scoped Flyway Jobs, startup/readiness/liveness probes, non-root/read-only containers, scoped
  NetworkPolicy, digest-pinned promotion templates, external Secret contracts, Kafka SASL/TLS,
  PostgreSQL CA mounts/TLS parameters, and Account/Risk gRPC mTLS. Spring services include Actuator,
  all application services expose the basic health/info/metrics endpoint, and critical delivery
  paths register Micrometer counters and observations. The local resilience contract also rejects
  raw FIX message logging and unsafe account-payload templates. `scripts/test-kubernetes-overlays.sh`
  renders and structurally validates all four overlays. The executable local overlay now also contains the node-local PostgreSQL
  singleton, disposable Redis cache, three-broker KRaft StatefulSet, bounded Flyway/PostgreSQL
  readiness gates, and explicit Kafka topic provisioning; focused manifest tests pass. PostgreSQL
  URI TLS parameters are preserved by the shared adapter.
  Risk now owns a durable `matching.commands` delivery observer that correlates exact Debezium
  event identities, proves its Kafka consumer group is caught up before refreshing admission lag,
  and exports backlog, oldest-event-age, and durable-refresh-timestamp gauges. The retained local
  gate registers all three service-owned connectors and executes the disposable connector
  outage/recovery check before the main Compose phases. Its full Kubernetes profile also pauses and
  resumes the Risk connector while
  checking durable lag, Actuator gauges, exact event observation, health endpoints, and sensitive
  ECS logs. The overlays structurally require ECS service/environment logging. The retained
  source-aligned production-like run passed all 62/62 phases, including all three connector
  registrations, CDC recovery, workload/fleet, and retained-run provenance; evidence is under
  `out/certification/issue-138-20260904-post-squash/`.
- **Missing behavior:** None for the repository-owned project target. Complete OpenTelemetry
  propagation/collection, a Prometheus server, dashboards, external alerts, and a tracing backend
  are future observability work rather than side-project completion blockers.
  Real registry digests/endpoints/CIDRs, environment-owned Secrets, external Flyway runners, and
  environment-owned collector/agent installation remain future promotion-template work. The
  committed overlay values are deliberately placeholders and cannot be treated as a live external
  security or observability gate.
- **Acceptance criteria:** Required secrets and staging/production security fail closed. Applications
  do not migrate at startup. Connectors can reach only their owning outboxes. Liveness represents
  process health; readiness represents business-role availability. Local checks can inspect the
  basic health/metrics endpoints and the required delivery/recovery metrics. Structured logs expose
  no secrets, complete account payload, or raw FIX payload by default.
- **Blocking dependencies:** KC-1, RM-1, PS-1, AC-1, AR-1, FG-1, MD-1, QS-1, GO-1, and KD-1.
- **GitHub issue:** [#138](https://github.com/WenHsuanYu/SimpleMatch/issues/138).

### CF-1: Use the shared Boot-managed Account DataSource adapter

- **Current status:** `COMPLETED`
- **Target behavior:** Account persistence uses the canonical `simplematch.postgres.dsn` through
  shared Boot DataSource auto-configuration; Account owns only schema and pool policy, Flyway does
  not start through datasource creation, blank/malformed/unsupported DSNs fail closed, and H2
  profiles remain usable for tests.
- **Current evidence:** `SimpleMatchDataSourceAutoConfiguration` is registered through Boot's
  auto-configuration imports and Account supplies `account_service`, pool size four, and a stable
  pool name. The Account context test supplies a competing `spring.datasource.url` and verifies the
  canonical H2 DSN wins; typed settings and malformed/unsupported DSN tests pass.
- **Missing behavior:** None in the repository slice; production PostgreSQL TLS credentials remain
  an environment Secret contract under PD-1.
- **GitHub issue:** [#140](https://github.com/WenHsuanYu/SimpleMatch/issues/140).

### KC-1: Provision durable Matching Kafka topics

- **Current status:** `COMPLETED`
- **Target behavior:** Repository-managed infrastructure provisions `matching.commands` and
  `matching.events` with 15 partitions, replication factor 3, minimum ISR 2, delete-only cleanup,
  30-calendar-day retention, disabled unclean leader election, and disabled automatic topic
  creation. Producers use `acks=all` and idempotence.
- **Current evidence:** `config/kafka/matching-production.properties` and the non-certifying local
  profile define the exact topology and producer policy. Repository scripts provision and
  fail-closed validate both topics, including every partition and topic-wide replica identity set,
  leader/ISR membership, broker safety settings, non-compaction, producer `acks=all`/idempotence,
  and workload-based 30-day capacity/headroom evidence. Fixture tests cover one- and two-broker
  loss, leader loss, unsafe ISR/broker state, unsafe producer settings, insufficient capacity, and
  refusal to use the RF1 local profile. The local certification runner passes the producer and
  capacity evidence to this validator; the durability runbook documents sizing, headroom, alerts,
  and the local failure matrix. Existing Risk and native Matching producers already enforce the
  required producer settings.
- **Missing behavior:** None for the repository-owned project target. External Kafka ownership,
  external disk measurements, and external production certification remain promotion-template work.
- **Acceptance criteria:** Local production-like readiness fails if partition count or durability
  settings differ. Neither topic is compacted. Thirty days of the certified workload fit with
  operational headroom. Local replication factor 1 cannot pass the production-shaped durability
  gate.
- **Blocking dependencies:** None for the repository-owned project target.
- **GitHub issue:** [#125](https://github.com/WenHsuanYu/SimpleMatch/issues/125).

### PC-1: Certify capacity, latency, and recovery

- **Current status:** `COMPLETED`
- **Target behavior:** A reproducible benchmark fixes hardware, CPU affinity, wait strategy,
  150-book distribution, workload mix/depth/rate, warmup, and measurement definitions.
- **Current evidence:** `simplematch-matching-capacity-benchmark` runs a fixed 150-book distribution
  with explicit warmup and measured iterations, records core p50/p99/p99.9/max latency, throughput,
  peak RSS, and measured loss/duplicate counters, and the wrapper records the host, CPU shape,
  requested CPU set, and effective benchmark-process affinity in a JSON report. The benchmark now
  replays the same workload on a fresh core and fails when measured and replay state, event fields,
  or serialized event bytes differ. It is a direct-core integrity/capacity gate, not a production
  performance claim. `scripts/run-matching-deployed-certification.sh` now collects read-only
  deployed evidence for the canonical cluster: StatefulSet/pod readiness, Pod UID to Node and
  image identity, 5/5/5 placement, process CPU allowance, cgroup CPU quota, PVC/Lease snapshots,
  and the native benchmark report. It returns `INCOMPLETE` when the deployed fleet or required
  Kafka/recovery measurement file is absent; it cannot turn a native benchmark into deployed Kafka
  evidence.
- **Completed evidence and limits:** Fresh deployed evidence covers marker-batch zero-loss/duplicate
  checks, per-event latency correlation, process-crash replay, normal Pod replacement, one worker
  stop and same-worker recovery, 15-pod 5/5/5 placement, writer CPU-allowance evidence, and local
  60-second replay/120-second replacement bounds. A separate broker replacement evidence run proves
  one broker Pod loss, two-broker data-plane continuity, and same-PVC ISR recovery. The bounded
  local-day profile fixes 150 books, 34 measured iterations, 10 cycles, 102,000 commands/events,
  a 256 resting-order bound, warmup, rate, deterministic checksums, RSS, latency percentiles,
  throughput, and zero loss/duplicates. The local PVC request envelope is 87 GiB and is logical
  local-path reservation rather than immediate allocation. The rendered local overlay requests
  about 38.63 GiB in steady state and about 45.75 GiB while one-shot bootstrap Jobs are present;
  this is a documented local resource-budget limitation, not a production capacity claim.
  The profile is not a 24-hour wall-clock endurance run, and external hardware, cluster, or
  production certification is not part of this project's target.
- **Acceptance criteria:** Report core and Kafka end-to-end p50/p99/p99.9/max, RSS, ring occupancy,
  commands/events per second, and zero-loss recovery. Engine replay reaches lag zero within 60
  seconds after Lease/baseline/Kafka availability; total replacement target is 120 seconds. If
  full-day replay misses 60 seconds, open a separate snapshot design issue. No microsecond-level
  external production claim is made by this repository gate.
- **Blocking dependencies:** ME-1, ME-2, ME-3, KD-1, and KC-1.
- **GitHub issue:** [#136](https://github.com/WenHsuanYu/SimpleMatch/issues/136).

### CL-1: Retire pre-release compatibility and superseded runtime seams

- **Current status:** `COMPLETED`
- **Target behavior:** Remove migration-only v1 order/Risk/Matching seams and every superseded
  runtime Market Reference path after replacement consumers are ready. Preserve FIX
  anti-corruption, WAL-to-Risk mapping, persistence mapping, and Java/C++ wire fixtures.
- **Current evidence:** #120 and #139 are complete. The implementation removes Risk v1 admission
  storage/outbox seams, Account v1 transport, obsolete Matching contracts and topic settings,
  runtime Market Reference publication/projection, and migration-only schemas/jobs. Final Java and
  native fixtures, Flyway checks, rendered Kubernetes contracts, and focused service tests pass.
- **Missing behavior:** None for the repository-owned pre-release target. The fresh #119 run passed
  all 59 phases, including the v2-only deployment contract, service-scoped Flyway jobs, CDC
  delivery, 15-pod fleet, and retained-run provenance.
- **Acceptance criteria:** No production caller, persisted required state, external consumer, or
  recovery path depends on a removed seam. All replacement paths preserve identity, ordering,
  retry, recovery, and error semantics. Repository validation remains truthful.
- **Blocking dependencies:** None. All replacement capabilities above and the #119 source-aligned
  local production-like gate are complete.
- **GitHub issue:** [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119); completed
  predecessor work is recorded in [#120](https://github.com/WenHsuanYu/SimpleMatch/issues/120) and
  [#139](https://github.com/WenHsuanYu/SimpleMatch/issues/139).

## Delivery order and issue mapping

GitHub Issues are the executable task source of truth. The accepted native sub-issue hierarchy under
[#10](https://github.com/WenHsuanYu/SimpleMatch/issues/10) records the historical dependency order;
all Phase 1 issues in this inventory are now completed:

1. [#121](https://github.com/WenHsuanYu/SimpleMatch/issues/121),
   [#122](https://github.com/WenHsuanYu/SimpleMatch/issues/122),
   [#123](https://github.com/WenHsuanYu/SimpleMatch/issues/123), then
   [#124](https://github.com/WenHsuanYu/SimpleMatch/issues/124).
2. [#139](https://github.com/WenHsuanYu/SimpleMatch/issues/139) may proceed independently;
   [#125](https://github.com/WenHsuanYu/SimpleMatch/issues/125) and
   [#126](https://github.com/WenHsuanYu/SimpleMatch/issues/126) establish the command path.
3. [#127](https://github.com/WenHsuanYu/SimpleMatch/issues/127),
   [#128](https://github.com/WenHsuanYu/SimpleMatch/issues/128), and
   [#129](https://github.com/WenHsuanYu/SimpleMatch/issues/129).
4. [#130](https://github.com/WenHsuanYu/SimpleMatch/issues/130),
   [#131](https://github.com/WenHsuanYu/SimpleMatch/issues/131),
   [#132](https://github.com/WenHsuanYu/SimpleMatch/issues/132), and
   [#133](https://github.com/WenHsuanYu/SimpleMatch/issues/133) after #129.
5. [#137](https://github.com/WenHsuanYu/SimpleMatch/issues/137) follows its artifact, Matching Event,
   and Account lifecycle blockers.
6. [#134](https://github.com/WenHsuanYu/SimpleMatch/issues/134) and
   [#135](https://github.com/WenHsuanYu/SimpleMatch/issues/135), then the integrated cross-service
   deployment/security gate in [#138](https://github.com/WenHsuanYu/SimpleMatch/issues/138).
7. [#136](https://github.com/WenHsuanYu/SimpleMatch/issues/136).
8. [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119) cleanup completed after its native
   blockers passed.

The parent architecture program [#10](https://github.com/WenHsuanYu/SimpleMatch/issues/10) is closed
as completed. Existing delivery-policy issue [#92](https://github.com/WenHsuanYu/SimpleMatch/issues/92)
remains the retained Risk/Account outbox foundation. [#87](https://github.com/WenHsuanYu/SimpleMatch/issues/87)
and #93-#99 are closed historical records for the superseded runtime Market Reference/legacy
delivery program; they do not prove the new Matching path complete.

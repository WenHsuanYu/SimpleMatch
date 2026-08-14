# Phase 1 Trading Release Remaining-Work Inventory

This is the canonical implementation-status inventory for the complete Phase 1 Trading Release:
the daily Market Reference Artifact, Risk-to-Matching routing, deterministic Matching, downstream
durability, required read paths, deployment/security, certification, and pre-release cleanup. The
canonical release-scope definition lives in
[`system-boundaries.md`](../services/docs/architecture/system-boundaries.md#phase-1-trading-release-boundary).
Architecture documents describe the accepted target. This document alone distinguishes that target
from the repository's current implementation state.

Status was reconciled against the `master` worktree on 2026-08-13. An accepted design is not
`COMPLETED` until the repository contains its implementation and local production-like verification
evidence. External production certification and live staging/production promotion are not goals of
this project. Their deployment values and run sequence remain template work with placeholders and
are neither current completion criteria nor blockers.

## Status vocabulary

| Status | Meaning |
| --- | --- |
| `COMPLETED` | The current repository contains the required behavior and the local production-like gate has passed. |
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
| Local gate or operational verification pending | RM-1, ME-1, ME-2, ME-3, PS-1, AC-1, FG-1, QS-1, PC-1 | The primary remaining work is to run the retained implementation through the local production-like dependency, restart, replay, and end-to-end scenarios. PS-1, AC-1, and FG-1 also retain their explicitly named status-adapter work. |
| Implementation or capability-specific local verification pending | MD-1, GO-1, PD-1 | The repository implementation and structural gates now exist, and the complete local gate has passed; capability-specific subscriber, collector, connector, security, and outage evidence is still required. |
| Compatibility or legacy cleanup | MR-5, CL-1 | Superseded runtime paths and migration-only seams still require source/configuration removal; local certification alone cannot close them. |

## Latest verification evidence (2026-08-13)

- Native CTest now passes all 40 tests, including Kubernetes Lease timestamp formatting and a
  minimum one-second timeout for Kafka recovery metadata queries.
- A disposable single-node `simplematch-live` kind cluster ran one real `matching-0` process against
  an in-cluster Kafka broker. The process acquired and renewed its partition Lease, mounted a
  `ReadWriteOncePod` PVC, consumed the Open Barrier plus two order commands, reached `READY`,
  committed input offset 3, and published two `matching.events` records.
- A normal Pod restart demonstrated Lease handover from the old Pod UID to a new UID, PVC baseline
  replay, recovery to `READY`, zero Kafka lag, and no additional output events. This is an
  integration smoke only: the cluster had one node, one Kafka broker with replication factor 1,
  local-path storage, and no external production-platform certification. Those platform-specific
  controls are intentionally outside this project's acceptance boundary.
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
- Risk and Account use one final typed v2 reservation RPC before Account v1 transport is removed.
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
| Runtime Market Reference publication stack | `OBSOLETE_TO_REMOVE` | [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119) |
| Risk artifact loading and `matching.commands` publication | `PARTIAL` | [#126](https://github.com/WenHsuanYu/SimpleMatch/issues/126) |
| Native deterministic Matching runtime | `PARTIAL` | [#127](https://github.com/WenHsuanYu/SimpleMatch/issues/127) |
| Kafka journal recovery and trading-day barriers | `PARTIAL` | [#128](https://github.com/WenHsuanYu/SimpleMatch/issues/128) |
| `matching.events` wire identity and publication | `PARTIAL` | [#129](https://github.com/WenHsuanYu/SimpleMatch/issues/129) |
| Permanent PostgreSQL trades and fills | `PARTIAL` | [#130](https://github.com/WenHsuanYu/SimpleMatch/issues/130) |
| Account critical Matching-event consumption | `PARTIAL` | [#131](https://github.com/WenHsuanYu/SimpleMatch/issues/131) |
| Final Account reservation v2 RPC | `COMPLETED` | [#139](https://github.com/WenHsuanYu/SimpleMatch/issues/139) |
| Account DataSource Boot auto-configuration | `COMPLETED` | [#140](https://github.com/WenHsuanYu/SimpleMatch/issues/140) |
| Durable QuickFIX execution delivery | `PARTIAL` | [#132](https://github.com/WenHsuanYu/SimpleMatch/issues/132) |
| Runtime market-data projection | `PARTIAL` | [#133](https://github.com/WenHsuanYu/SimpleMatch/issues/133) |
| Required query service and Redis read models | `PARTIAL` | [#137](https://github.com/WenHsuanYu/SimpleMatch/issues/137) |
| Gateway operational admission control | `PARTIAL` | [#135](https://github.com/WenHsuanYu/SimpleMatch/issues/135) |
| Matching StatefulSet ownership and fencing | `COMPLETED` | [#134](https://github.com/WenHsuanYu/SimpleMatch/issues/134) |
| Cross-service deployment, security, and observability | `PARTIAL` | [#138](https://github.com/WenHsuanYu/SimpleMatch/issues/138) |
| Production-shaped Kafka topic profile | `COMPLETED` | [#125](https://github.com/WenHsuanYu/SimpleMatch/issues/125) |
| Performance and recovery certification | `PARTIAL` | [#136](https://github.com/WenHsuanYu/SimpleMatch/issues/136) |
| Pre-release compatibility and legacy cleanup | `PARTIAL` | [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119), [#120](https://github.com/WenHsuanYu/SimpleMatch/issues/120) |

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

- **Current status:** `OBSOLETE_TO_REMOVE`
- **Target behavior:** No runtime Market Reference process, PostgreSQL snapshot/routing tables,
  outbox, Debezium connector, Kafka topic, Risk projection consumer, or Matching routing-policy
  ingress remains.
- **Current evidence:** `services/marketdata-publisher`, its Flyway migrations and outboxes,
  `deploy/*marketdata-publisher-outbox*`, Risk's `routing` package, and native
  `routing_policy_ingress` implement the superseded design.
- **Missing behavior:** Preserve reusable pure normalization/validation code in the offline builder,
  migrate consumers to startup artifact loading, then remove runtime wiring, configuration,
  manifests, contracts, tests, and documentation.
- **Acceptance criteria:** Repository search finds no runtime publication or consumption of
  `market-reference.snapshots` or `market-reference.routing-policies`; Risk and Matching readiness
  prove the mounted artifact identity instead.
- **Blocking dependencies:** MR-1 through MR-4, RM-1, and ME-1.
- **GitHub issue:** [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119), section B; its
  native blockers are the replacement issues above.

### RM-1: Load the artifact in Risk and publish Matching commands

- **Current status:** `PARTIAL`
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
  workers, and the certification runner registers `risk-service-outbox` only after Flyway completes,
  then requires the connector and task to report `RUNNING`.
- **Missing behavior:** A full local production-like run still needs to execute that deployed Risk
  connector against the repository-owned three-broker Kafka profile and prove a real accepted
  command reaches `matching.commands`; the static deployment and registration contract is now in
  place. The offline builder and production artifact approval workflow remain MR-1 through MR-4
  work rather than being supplied by Risk.
- **Acceptance criteria:** New order, cancel, `TRADING_DAY_OPEN_BARRIER`, and
  `TRADING_DAY_CLOSE_BARRIER` records target explicit partitions 0-14. Recovery never recomputes an
  admitted route. No command is published for a stale or mismatched artifact.
- **Blocking dependencies:** MR-3, MR-4, and KC-1.
- **GitHub issue:** [#126](https://github.com/WenHsuanYu/SimpleMatch/issues/126).

### ME-1: Build the native single-writer Matching runtime

- **Current status:** `PARTIAL`
- **Target behavior:** Each native `matching-N` contains a Kafka ingress thread, preallocated SPSC
  input ring, one CPU-pinned single-writer core owning at most 150 order books, preallocated SPSC
  output ring, and Kafka publisher/offset coordinator. The core performs no network or disk I/O,
  locks, or post-warmup allocation.
- **Current evidence:** The native runtime has preallocated SPSC ingress/output rings, a
  single-writer price-time order-book core capped at 150 instruments, command decoding, direct
  partition assignment, output backpressure, a librdkafka adapter, lifecycle executable/probes, and
  deterministic CTest coverage, including a bounded-capacity benchmark smoke and a Close Barrier
  regression that covers both sides of an order book, plus explicit input-ring, output-ring, and
  order-book-capacity checks. The capacity report now compares native state checksums and
  deterministic serialized event bytes, and also records the benchmark process's effective CPU
  affinity when pinning is requested. A local broker smoke and a disposable kind smoke have
  consumed real `matching.commands` records and published acknowledged `matching.events` records.
- **Missing behavior:** The production binary still polls Kafka and drives the partition coordinator
  in one loop; a separate Kafka-ingress/writer-thread split and live CPU pinning are not yet
  implemented. Local production-like CPU/resource mapping and end-to-end Kafka/ring throughput
  integration against the owned three-broker profile also remain. The direct-core allocation and
  throughput smoke is evidence only for the native core; external hardware or production-cluster
  certification is not required by this project. Those runtime adapters must not enter the Matching
  core hot path.
- **Acceptance criteria:** The same ordered command stream and pinned binary produce identical state
  checksums and event bytes. Ring exhaustion never overwrites, drops, or expands heap storage.
  Output backpressure stalls safely and drives the accepted admission policy.
- **Blocking dependencies:** MR-3, RM-1, and KC-1. ME-2 builds on this capability rather than
  forming a circular prerequisite.
- **GitHub issue:** [#127](https://github.com/WenHsuanYu/SimpleMatch/issues/127).

### ME-2: Recover from Kafka and enforce trading-day barriers

- **Current status:** `PARTIAL`
- **Target behavior:** `matching.commands` is the authoritative replicated input journal. An Open
  Barrier defines the daily replay baseline; a Close Barrier expires ROD orders and closes the
  partition deterministically. PVC metadata is an acceleration index, not the authority.
- **Current evidence:** `PartitionReplayCoordinator` models explicit partition assignment,
  Open/Close barriers, command de-duplication, retained-record replay, output ACK tracking, and a
  contiguous commit watermark; CTests cover the crash/replay and barrier invariants. The native
  librdkafka adapter now exposes retained-range reads, committed/end offsets, seeking, and
  synchronous commits, while the runtime replays the PVC baseline before live polling.
- **Missing behavior:** The disposable kind smoke covered PVC baseline persistence, Kafka replay,
  Lease handover, and a normal Pod restart, but local production-like retention, broker/PVC failure
  behavior, and the owned three-broker recovery gate remain outstanding. Unit tests and a
  single-node smoke do not substitute for that local operational recovery gate; external production
  certification is outside the project boundary.
- **Acceptance criteria:** Outputs are ACKed before the input offset becomes completed; commits
  never cross a gap. Crash windows may replay identical events but cannot lose an accepted command.
  A missing retained Open Barrier fails closed. No periodic order-book snapshot is added unless the
  recovery certification misses its SLO.
- **Blocking dependencies:** ME-1 and KC-1.
- **GitHub issue:** [#128](https://github.com/WenHsuanYu/SimpleMatch/issues/128).

### ME-3: Publish deterministic Matching Events

- **Current status:** `PARTIAL`
- **Target behavior:** `matching.events` carries `ORDER_RESTED`, `TRADE_EXECUTED`,
  `ORDER_CANCELLED`, and `ORDER_EXPIRED`. One trade event describes both maker and taker legs.
- **Current evidence:** `matching_runtime_v1.proto`, the native event encoder, deterministic
  event/trade identity, output/match indices, raw-byte hash fixtures, and Java envelope parsing are
  implemented and covered by native and shared-contract tests. The native idempotent producer now
  checks delivery callbacks, and a local broker smoke has verified acknowledged event publication
  and the deterministic record key; the disposable kind smoke observed two published event keys and
  retained that count across a normal Matching restart.
- **Missing behavior:** The producer must be certified against the production 15-partition,
  three-broker profile, including ACK/replay and schema/image compatibility at deployment time.
- **Acceptance criteria:** `eventId` derives from identity version, trading session, partition,
  command, and output index; `tradeId` uses command and match index. Event type is not part of
  `eventId`. Consumers hash the exact Kafka record value bytes. Same ID/same hash is a duplicate;
  same ID/different hash is quarantined as a deterministic violation. C++ golden bytes parse in
  every Java critical consumer.
- **Blocking dependencies:** ME-1 and KC-1.
- **GitHub issue:** [#129](https://github.com/WenHsuanYu/SimpleMatch/issues/129).

### PS-1: Permanently store trades and order-fill legs

- **Current status:** `PARTIAL`
- **Target behavior:** Persistence consumes every `matching.events` partition and atomically stores
  inbox identity/hash, one immutable trade, maker/taker order-fill legs, and order projections.
- **Current evidence:** Flyway V3 creates a raw-hash inbox, immutable `trades` and `order_fills`,
  projections, progress, and quarantine. The critical consumer applies a final Matching Event in one
  transaction and commits its Kafka acknowledgement only afterward; focused store, consumer, and
  migration tests pass.
- **Missing behavior:** A local production-like PostgreSQL/Kafka failure-and-restart certification is
  still required, as are the operational status endpoint consumed later by GO-1 and local deployment
  wiring. External production deployment evidence is a future promotion concern, not a project
  blocker.
- **Acceptance criteria:** DB commit precedes Kafka offset commit. IDs use 32-byte binary columns
  with exact-length checks; quantities are `BIGINT` shares; prices are `BIGINT` in 1/10,000 TWD;
  trading day is `DATE`; partition is constrained to 0-14. PostgreSQL outage is buffered by Kafka
  and never blocks the Matching hot path directly.
- **Blocking dependencies:** ME-3.
- **GitHub issue:** [#130](https://github.com/WenHsuanYu/SimpleMatch/issues/130).

### AC-1: Apply Matching Events to Account Authority

- **Current status:** `PARTIAL`
- **Target behavior:** Account consumes `matching.events` as a critical consumer and applies both
  sides' fills or terminal releases exactly once in local transactions.
- **Current evidence:** Flyway V7, the final-event account application service, durable inbox,
  payload hash validation, maker/taker fill mapping, quarantine, and manual acknowledgement are
  implemented and covered by focused and application-context tests.
- **Missing behavior:** Local production-like PostgreSQL/Kafka restart certification and an
  operational status adapter remain required; the independent Account reservation-RPC cutover is
  tracked separately by AR-1. External production certification is not required.
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
- **Missing behavior:** None for the repository-owned project target. The retained Account v1 server
  remains intentionally available until #119 performs the later compatibility cleanup; external
  production deployment proof and staging/production configuration are promotion-template work.
- **Acceptance criteria:** The Account transaction remains service-owned and no Risk transaction is
  held across the RPC. Equivalent retries preserve one reservation outcome; conflicting retries are
  typed conflicts; remote success followed by Risk failure recovers without reserving twice.
- **Blocking dependencies:** None; the typed Account Authority and durable Admission foundations
  already exist.
- **GitHub issue:** [#139](https://github.com/WenHsuanYu/SimpleMatch/issues/139).

### FG-1: Deliver Matching Events durably over FIX

- **Current status:** `PARTIAL`
- **Target behavior:** The single QuickFIX Gateway consumes `matching.events` critically, stores a
  durable event inbox and per-order delivery ledger, and emits stable trade, rest, cancel, expiry,
  IOC, and FOK lifecycle reports through a JDBC-backed QuickFIX message store.
- **Current evidence:** Gateway Flyway V1 now creates a durable inbox, exact raw hash evidence,
  delivery ledger, progress, quarantine, and JDBC QuickFIX/J message-store tables. The final-event
  consumer uses strict retry/quarantine, deterministic delivery/Exec identities, and commits only
  after delivery intents persist; focused tests and QuickFIX certification tests pass.
- **Missing behavior:** A local production-like PostgreSQL/Kafka restart certification and the GO-1
  status adapter remain required. Socket delivery deliberately remains at-least-once and needs
  counterparty interoperability evidence; an externally operated production session is not required
  for this project.
- **Acceptance criteria:** Kafka offset commits only after all required delivery intents are
  durable. Socket delivery is at least once; retransmission preserves FIX session semantics and
  stable `ExecID`. Critical lifecycle reports cannot be skipped to an ordinary DLQ.
- **Blocking dependencies:** ME-3. GO-1 composes this consumer's status after durable delivery
  exists rather than forming a circular prerequisite.
- **GitHub issue:** [#132](https://github.com/WenHsuanYu/SimpleMatch/issues/132).

### MD-1: Build the non-critical market-data projection

- **Current status:** `PARTIAL`
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
  and the production profile enables the projection and Redis settings.
- **Missing behavior:** A real Kafka/PostgreSQL/Redis integration, gRPC subscriber smoke, and
  replay/rebuild run still need local production-like certification. The authorized private
  notification stream remains a separate compatibility boundary; only the public snapshot stream
  is implemented in this slice. Projection failure remains isolated from trading admission by
  design.
- **Acceptance criteria:** Projection failure does not affect Matching, permanent trade storage,
  Account, QuickFIX, or admission. Delayed retry/DLQ is allowed because the view can be rebuilt.
- **Blocking dependencies:** ME-3.
- **GitHub issue:** [#133](https://github.com/WenHsuanYu/SimpleMatch/issues/133).

### QS-1: Build the required Query capability and Redis read models

- **Current status:** `PARTIAL`
- **Target behavior:** A required Phase 1 `query-service` exposes read-only order, execution,
  account-summary, and active-market-reference views from query-owned PostgreSQL and Redis
  projections. It is non-critical to trading admission but not optional for release completion.
- **Current evidence:** `services/query-service` now provides the separate Spring service, Flyway
  inbox/checkpoint/read-model schema, asynchronous final Matching and Account lifecycle consumers,
  versioned read APIs, active-artifact installation seam, freshness metadata, replay reset, and
  optional Redis read-through fallback. Cache read and write failures fall back to the durable
  PostgreSQL projection. Focused H2 projection and cache-fallback tests pass.
- **Missing behavior:** Local production-like Kafka/PostgreSQL/Redis deployment and
  outage/replay certification remain part of PD-1 and the repository release gate. The
  service-context test also proves the shared canonical-DSN/pool adapter and no competing
  `spring.datasource.*` source. External production certification is not a prerequisite.
- **Acceptance criteria:** Query never reads another service's database or scans Kafka synchronously.
  Redis can be deleted and rebuilt; misses/outages fall back to PostgreSQL; responses disclose
  freshness; and Query failure cannot pause any critical trading component.
- **Blocking dependencies:** MR-3, ME-3, and AC-1.
- **GitHub issue:** [#137](https://github.com/WenHsuanYu/SimpleMatch/issues/137).

### GO-1: Operate one Gateway admission authority

- **Current status:** `PARTIAL`
- **Target behavior:** One Gateway starts `PRE_OPEN` and exposes `status`, `open`,
  `pause-new-orders`, `interrupt-market`, and `close-day`. It automatically closes at session end
  and automatically pauses new orders when critical readiness becomes unsafe.
- **Current evidence:** The Gateway now starts `PRE_OPEN` with the five accepted admission states;
  it keeps cancellation available only during `NEW_ORDERS_PAUSED`. A pure
  `TradingSystemStatusEvaluator` verifies 15 owners, identities, recovery/lag, quarantine, Kafka
  topology, stale status, and critical-consumer age. The controller requires three fresh ready
  observations to open, auto-pauses/interrupts, auto-closes in Asia/Taipei time, never auto-reopens,
  records operations in Flyway V2, and exposes a fixed five-command application boundary. Focused
  state-machine, controller, audit, ingress, migration, and application-context tests pass.
- **Missing behavior:** Infrastructure adapters must still collect local production-like Risk,
  Matching Lease/readiness, Kafka end-offset, Persistence, Account, and QuickFIX facts into one
  observation. The authenticated HTTP adapter now accepts the five fixed commands and normalized
  `TradingSystemObservation` reports, but it is disabled by default and does not invent those live
  facts. Until then a deployed Gateway remains `PRE_OPEN`; local end-to-end cluster verification
  belongs with the deployment/security work in PD-1. External production certification is outside
  the project target.
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

- **Current status:** `PARTIAL`
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
  several services expose its metrics endpoint, and critical delivery paths register Micrometer
  counters and observations. `scripts/test-kubernetes-overlays.sh` renders and structurally validates
  all four overlays. The executable local overlay now also contains the node-local PostgreSQL
  singleton, disposable Redis cache, three-broker KRaft StatefulSet, bounded Flyway/PostgreSQL
  readiness gates, and explicit Kafka topic provisioning; focused manifest tests pass. PostgreSQL
  URI TLS parameters are preserved by the shared adapter.
- **Missing behavior:** A retained two-replica Debezium Connect worker, endpoint/secret/TLS contract,
  and staging/production overlay template are now represented. Local connector registration,
  local dependency-outage smoke, consistent structured log fields, consistent basic health/metrics
  exposure, key metric assertions, and automated sensitive-log checks still require completion.
  Complete OpenTelemetry propagation/collection, a Prometheus server, dashboards, external alerts,
  and a tracing backend are future observability work rather than side-project completion blockers.
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

- **Current status:** `PARTIAL`
- **Target behavior:** A reproducible benchmark fixes hardware, CPU affinity, wait strategy,
  150-book distribution, workload mix/depth/rate, warmup, and measurement definitions.
- **Current evidence:** `simplematch-matching-capacity-benchmark` runs a fixed 150-book distribution
  with explicit warmup and measured iterations, records core p50/p99/p99.9/max latency, throughput,
  peak RSS, and measured loss/duplicate counters, and the wrapper records the host, CPU shape,
  requested CPU set, and effective benchmark-process affinity in a JSON report. The benchmark now
  replays the same workload on a fresh core and fails when measured and replay state, event fields,
  or serialized event bytes differ. It is a direct-core integrity/capacity gate, not a production
  performance claim.
- **Missing behavior:** Kafka end-to-end latency, ring occupancy, workload-depth/rate calibration,
  soak tests, broker-outage tests, and 15-pod deployment recovery evidence still require the local
  production-like scenarios. External hardware, cluster, or production certification is not part of
  this project's target.
- **Acceptance criteria:** Report core and Kafka end-to-end p50/p99/p99.9/max, RSS, ring occupancy,
  commands/events per second, and zero-loss recovery. Engine replay reaches lag zero within 60
  seconds after Lease/baseline/Kafka availability; total replacement target is 120 seconds. If
  full-day replay misses 60 seconds, open a separate snapshot design issue. No microsecond-level
  external production claim is made by this repository gate.
- **Blocking dependencies:** ME-1, ME-2, ME-3, KD-1, and KC-1.
- **GitHub issue:** [#136](https://github.com/WenHsuanYu/SimpleMatch/issues/136).

### CL-1: Retire pre-release compatibility and superseded runtime seams

- **Current status:** `PARTIAL`
- **Target behavior:** Remove migration-only v1 order/Risk/Matching seams and every superseded
  runtime Market Reference path after replacement consumers are ready. Preserve FIX
  anti-corruption, WAL-to-Risk mapping, persistence mapping, and Java/C++ wire fixtures.
- **Current evidence:** Local commits remove the dead QuickFIX `orders.commands` publication
  capability tracked by #120. #119 remains open and the legacy Risk v1, Account v1, shared v1,
  Market Reference, old Matching topic, and old execution consumers remain.
- **Missing behavior:** Finish #119 in dependency order; reset pre-release schemas where accepted;
  complete the Account RPC cutover in #139; remove old topic names/contracts only after coordinated
  producer/consumer cutovers; update active compatibility inventories and certification.
- **Acceptance criteria:** No production caller, persisted required state, external consumer, or
  recovery path depends on a removed seam. All replacement paths preserve identity, ordering,
  retry, recovery, and error semantics. Repository validation remains truthful.
- **Blocking dependencies:** All replacement capabilities above.
- **GitHub issue:** [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119) and
  [#120](https://github.com/WenHsuanYu/SimpleMatch/issues/120), with Account RPC replacement in
  [#139](https://github.com/WenHsuanYu/SimpleMatch/issues/139).

## Delivery order and issue mapping

GitHub Issues are the executable task source of truth. The accepted native sub-issue hierarchy under
[#10](https://github.com/WenHsuanYu/SimpleMatch/issues/10) encodes this dependency order:

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
8. [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119) cleanup only after its native
   blockers pass.

The parent architecture program remains
[#10](https://github.com/WenHsuanYu/SimpleMatch/issues/10). Existing delivery-policy issue
[#92](https://github.com/WenHsuanYu/SimpleMatch/issues/92) remains the retained Risk/Account outbox
foundation. [#87](https://github.com/WenHsuanYu/SimpleMatch/issues/87) and #93-#99 are closed
historical records for the superseded runtime Market Reference/legacy delivery program; they do not
prove the new Matching path complete.

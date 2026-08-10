# Phase 1 Trading Release Remaining-Work Inventory

This is the canonical implementation-status inventory for the complete Phase 1 Trading Release:
the daily Market Reference Artifact, Risk-to-Matching routing, deterministic Matching, downstream
durability, required read paths, deployment/security, certification, and pre-release cleanup. The
canonical release-scope definition lives in
[`system-boundaries.md`](../services/docs/architecture/system-boundaries.md#phase-1-trading-release-boundary).
Architecture documents describe the accepted target. This document alone distinguishes that target
from the repository's current implementation state.

Status was reconciled against the `master` worktree on 2026-08-11. An accepted design is not
`COMPLETED` until the repository contains its implementation and verification evidence.

## Status vocabulary

| Status | Meaning |
| --- | --- |
| `COMPLETED` | The current repository contains the required behavior and verification evidence. |
| `PARTIAL` | A reusable foundation exists, but the accepted target behavior is incomplete. |
| `NOT_STARTED` | No production implementation of the target capability exists. |
| `OBSOLETE_TO_REMOVE` | Current code implements a superseded design and must be removed or migrated. |

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
- Kafka `matching.commands` is the authoritative durable ordered input journal. A local per-command
  Matching journal is not part of the target architecture.
- Each native Matching process uses a Kafka ingress thread, a preallocated input ring, one
  single-writer Matching core, a preallocated output ring, and a Kafka publisher/coordinator.
- PostgreSQL is the permanent trade and projection store, but it is not used to recover Matching
  order books.
- The required Query capability owns rebuildable PostgreSQL and Redis projections and never reads a
  different service's database. Query failure degrades reads but cannot pause the trading path.
- Risk and Account use one final typed v2 reservation RPC before Account v1 transport is removed.
- One QuickFIX Gateway owns Phase 1 FIX sessions. It starts `PRE_OPEN`; admission opens only after
  the accepted readiness checks pass.
- Every Phase 1 workload passes the accepted Kubernetes overlay, Secret, transport-security,
  migration-job, connector, network-policy, readiness, telemetry, and deployment gates.
- Kafka delivery is at least once. Deterministic identities plus consumer-owned inboxes make local
  business effects idempotent.

## Summary

| Capability | Current status | Primary tracker |
| --- | --- | --- |
| Offline official-source acquisition and normalization | `PARTIAL` | [#121](https://github.com/WenHsuanYu/SimpleMatch/issues/121) |
| Candidate/final artifact workflow and approval evidence | `NOT_STARTED` | [#124](https://github.com/WenHsuanYu/SimpleMatch/issues/124) |
| Canonical artifact schema, identity, and packaging | `NOT_STARTED` | [#122](https://github.com/WenHsuanYu/SimpleMatch/issues/122) |
| Stable 15-partition routing assignment | `PARTIAL` | [#123](https://github.com/WenHsuanYu/SimpleMatch/issues/123) |
| Runtime Market Reference publication stack | `OBSOLETE_TO_REMOVE` | [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119) |
| Risk artifact loading and `matching.commands` publication | `PARTIAL` | [#126](https://github.com/WenHsuanYu/SimpleMatch/issues/126) |
| Native deterministic Matching runtime | `PARTIAL` | [#127](https://github.com/WenHsuanYu/SimpleMatch/issues/127) |
| Kafka journal recovery and trading-day barriers | `NOT_STARTED` | [#128](https://github.com/WenHsuanYu/SimpleMatch/issues/128) |
| `matching.events` wire identity and publication | `PARTIAL` | [#129](https://github.com/WenHsuanYu/SimpleMatch/issues/129) |
| Permanent PostgreSQL trades and fills | `PARTIAL` | [#130](https://github.com/WenHsuanYu/SimpleMatch/issues/130) |
| Account critical Matching-event consumption | `PARTIAL` | [#131](https://github.com/WenHsuanYu/SimpleMatch/issues/131) |
| Final Account reservation v2 RPC | `PARTIAL` | [#139](https://github.com/WenHsuanYu/SimpleMatch/issues/139) |
| Durable QuickFIX execution delivery | `PARTIAL` | [#132](https://github.com/WenHsuanYu/SimpleMatch/issues/132) |
| Runtime market-data projection | `NOT_STARTED` | [#133](https://github.com/WenHsuanYu/SimpleMatch/issues/133) |
| Required query service and Redis read models | `NOT_STARTED` | [#137](https://github.com/WenHsuanYu/SimpleMatch/issues/137) |
| Gateway operational admission control | `PARTIAL` | [#135](https://github.com/WenHsuanYu/SimpleMatch/issues/135) |
| Matching StatefulSet ownership and fencing | `NOT_STARTED` | [#134](https://github.com/WenHsuanYu/SimpleMatch/issues/134) |
| Cross-service deployment, security, and observability | `PARTIAL` | [#138](https://github.com/WenHsuanYu/SimpleMatch/issues/138) |
| Production Kafka topic profile | `NOT_STARTED` | [#125](https://github.com/WenHsuanYu/SimpleMatch/issues/125) |
| Performance and recovery certification | `NOT_STARTED` | [#136](https://github.com/WenHsuanYu/SimpleMatch/issues/136) |
| Pre-release compatibility and legacy cleanup | `PARTIAL` | [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119), [#120](https://github.com/WenHsuanYu/SimpleMatch/issues/120) |

## Detailed inventory

### MR-1: Acquire and normalize official market facts

- **Current status:** `PARTIAL`
- **Target behavior:** An offline repository tool fetches official TWSE and TPEx company,
  instrument, calendar, reference-price, and price-limit data. It selects all Phase 1 eligible XTAI
  and ROCO regular-board common stocks and records explicit reasons for known but unsupported
  instruments. Yahoo Finance is not an authoritative source.
- **Current evidence:** `services/marketdata-publisher/.../snapshot` contains reusable instrument,
  tick-table, calendar, eligibility, canonical-codec, fixture, and validation types. It does not
  fetch or reconcile the accepted live official endpoints.
- **Missing behavior:** Implement source clients, retrieval metadata, source checksums, trading-day
  checks, cross-source reconciliation, and fail-closed handling for missing, stale, partial, or
  inconsistent rows.
- **Acceptance criteria:** Deterministic fixtures and live-source contract tests cover TWSE company
  data, TPEx company data, TWSE daily reference/limit prices, TPEx next-day reference/limit prices,
  and the official trading calendar. Every eligible instrument has complete identity, venue, lot,
  tick, reference, lower-limit, and upper-limit facts.
- **Blocking dependencies:** Versioned static Phase 1 classification and tick/session rules.
- **GitHub issue:** [#121](https://github.com/WenHsuanYu/SimpleMatch/issues/121).

### MR-2: Build preliminary and final daily artifacts

- **Current status:** `NOT_STARTED`
- **Target behavior:** D-1 produces a preliminary candidate containing the instrument universe,
  eligibility, and stable routing. On trading-day morning the builder re-fetches every official
  source, re-reconciles the universe, adds the official reference and limit prices, and produces the
  only final artifact that may open the market.
- **Current evidence:** The current runtime publication service can import fixture snapshots, but no
  candidate/final CLI workflow or approval report exists.
- **Missing behavior:** Candidate command, final command, anomaly/diff report, operator approval,
  exact source-date reconciliation, and fail-closed release gate.
- **Acceptance criteria:** Approval reviews summary counts, additions/removals, eligibility changes,
  route changes, source checksums, validation results, artifact size, delivery form, and
  `contentSha256`; it does not require manual inspection of every instrument row.
- **Blocking dependencies:** MR-1 and MR-3.
- **GitHub issue:** [#124](https://github.com/WenHsuanYu/SimpleMatch/issues/124).

### MR-3: Define artifact schema, identity, retention, and delivery

- **Current status:** `NOT_STARTED`
- **Target behavior:** One JSON envelope contains `metadata`, `marketRules`, `marketSnapshot`, and
  `routingPolicy`. Reusable tick tables are normalized at the top level. Instrument facts do not
  duplicate their routing partition.
- **Current evidence:** Current snapshot and routing codecs are separate runtime publication
  contracts and do not implement the accepted single envelope.
- **Missing behavior:** JSON schema, deterministic writer, exact UTF-8 hash contract, approval
  report, repository retention layout, ConfigMap/OCI packaging, startup mount path, and consumer
  validators.
- **Acceptance criteria:** Artifact identity is `tradingDay + contentSha256`. The checksum is not
  embedded in the JSON; it is supplied externally. Every eligible instrument has exactly one route,
  every unsupported instrument has none, and the declared partition count is 15. Approved output
  is retained under `config/market-reference/approved/YYYY-MM-DD/`. Artifacts up to 900 KiB use an
  immutable ConfigMap; larger artifacts use a digest-pinned OCI data image and init container. Both
  mount `/etc/simplematch/market-reference/market_reference.json`.
- **Blocking dependencies:** MR-1.
- **GitHub issue:** [#122](https://github.com/WenHsuanYu/SimpleMatch/issues/122).

### MR-4: Assign stable routes within fixed capacity

- **Current status:** `PARTIAL`
- **Target behavior:** Exactly 15 partitions exist, each with capacity for 150 instrument order
  books. Existing eligible instruments keep their previous partition; removals disappear; new
  instruments go to the least-loaded partition with the lowest partition ID breaking ties. The
  initial baseline sorts by `(venueMic, symbol)` before applying the same least-loaded rule.
- **Current evidence:** Runtime `RoutingPolicy` and `RoutingAssignment` types validate assignments,
  but they model the superseded publication lifecycle and do not implement the accepted stable
  allocator and fixed 15-by-150 capacity gate.
- **Missing behavior:** Baseline allocator, previous-approved-artifact input, exact-set validation,
  capacity diagnostics, deterministic fixtures, and operator diff output.
- **Acceptance criteria:** Rebuilding from identical inputs is byte-identical; adding one instrument
  does not move existing eligible instruments; no partition exceeds 150; more than 2,250 eligible
  instruments fails the build.
- **Blocking dependencies:** MR-3.
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
- **Current evidence:** Durable Admission, explicit routing provenance, partitioned outbox records,
  and backpressure exist. Routing currently comes from a Kafka projection or legacy local resolver,
  and the topic/contract remains `orders.validated`.
- **Missing behavior:** Startup loader, exact artifact validation, removal of routing projection and
  fallback resolver, `MatchingCommand` envelope, topic cutover, stable command identity, Open/Close
  Barrier publication, and matching-command CDC contract.
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
- **Current evidence:** `matching-engine` has CMake wiring and tested critical-ingress and routing
  state machines. It has no Kafka runtime, Disruptor-style rings, order book, matching algorithm, or
  publisher.
- **Missing behavior:** Native value types, ring implementation, deterministic order storage,
  price-time priority, limit/market and ROD/IOC/FOK behavior, cancellation, expiry, backpressure,
  event production, metrics, and lifecycle endpoints.
- **Acceptance criteria:** The same ordered command stream and pinned binary produce identical state
  checksums and event bytes. Ring exhaustion never overwrites, drops, or expands heap storage.
  Output backpressure stalls safely and drives the accepted admission policy.
- **Blocking dependencies:** MR-3, RM-1, and KC-1. ME-2 builds on this capability rather than
  forming a circular prerequisite.
- **GitHub issue:** [#127](https://github.com/WenHsuanYu/SimpleMatch/issues/127).

### ME-2: Recover from Kafka and enforce trading-day barriers

- **Current status:** `NOT_STARTED`
- **Target behavior:** `matching.commands` is the authoritative replicated input journal. An Open
  Barrier defines the daily replay baseline; a Close Barrier expires ROD orders and closes the
  partition deterministically. PVC metadata is an acceleration index, not the authority.
- **Current evidence:** Native ingress tests model retry/quarantine but no Kafka consumer, daily
  baseline, replay, offset commit, or barrier behavior exists.
- **Missing behavior:** Explicit `assign()`, Open/Close Barrier processing, baseline PVC metadata,
  Kafka scan fallback, state-only replay through the committed boundary, normal replay after that
  boundary, command deduplication, output-ACK tracking, contiguous offset watermark, and fail-closed
  retention checks.
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
- **Current evidence:** v1/v2 matching Protobuf types and Java v1 consumers exist, but there is no
  Matching producer and the current `matching.executions` contract does not implement the accepted
  identity or payload.
- **Missing behavior:** Final Protobuf contract, native producer, 15-partition output routing,
  deterministic `eventId`/`tradeId`, output/match indices, fixed-point fields, raw-record hash
  fixtures, publisher ACK tracker, and schema/version gate.
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
- **Current evidence:** `services/persistence` has a Spring Boot entry point and Flyway `orders`,
  `executions`, and `inbox` foundation. It has no Kafka runtime or projection writer, and the
  current `executions` table cannot represent one trade with two legs.
- **Missing behavior:** Replace the shallow execution model with `trades` and `order_fills`, add the
  critical consumer, transactional application service, repositories, quarantine, offsets/status,
  migration tests, and PostgreSQL integration tests.
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
- **Current evidence:** Account has reservation/fill/release behavior, a critical v1 execution
  consumer, inbox state, quarantine storage, and transactional tests. It does not consume the final
  two-leg Matching Event contract or store the accepted payload hash.
- **Missing behavior:** Contract cutover, maker/taker leg mapping, raw-record hash comparison,
  partition progress reporting, schema/session validation, and full PostgreSQL restart/ACK tests.
- **Acceptance criteria:** Inbox claim, payload-hash validation, account/reservation mutation,
  lifecycle outbox, and inbox completion commit atomically. A failed record never lets a later
  record overtake it.
- **Blocking dependencies:** ME-3.
- **GitHub issue:** [#131](https://github.com/WenHsuanYu/SimpleMatch/issues/131).

### AR-1: Migrate Account reservation RPC to the final v2 contract

- **Current status:** `PARTIAL`
- **Target behavior:** Risk and Account use one typed v2 reservation boundary for the durable
  Admission saga. The RPC carries accepted identities, whole-share quantity, fixed-point monetary
  values, reservation terms, and typed outcomes without legacy string parsing in domain behavior.
- **Current evidence:** Account Authority and durable Risk Admission application boundaries exist,
  but the production Account gRPC server and Risk reservation client still use `account.v1`.
- **Missing behavior:** Final Account v2 Protobuf contract, Account server adapter, Risk client,
  production wiring cutover, timeout/outcome mapping, saga recovery tests, and proof that no
  production caller remains on Account v1.
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
- **Current evidence:** FIX mapping, session state, WAL recovery, and v1 execution consumers exist.
  The configured runtime uses `FileStoreFactory`; the delivery consumer is explicitly non-critical,
  uses delayed retry/DLQ, and has only process-local deduplication.
- **Missing behavior:** PostgreSQL gateway schema, durable inbox, payload hash, delivery ledger,
  deterministic delivery/Exec identities, JDBC QuickFIX store, critical retry/quarantine, restart
  reconciliation, and consumer progress status.
- **Acceptance criteria:** Kafka offset commits only after all required delivery intents are
  durable. Socket delivery is at least once; retransmission preserves FIX session semantics and
  stable `ExecID`. Critical lifecycle reports cannot be skipped to an ordinary DLQ.
- **Blocking dependencies:** ME-3. GO-1 composes this consumer's status after durable delivery
  exists rather than forming a circular prerequisite.
- **GitHub issue:** [#132](https://github.com/WenHsuanYu/SimpleMatch/issues/132).

### MD-1: Build the non-critical market-data projection

- **Current status:** `NOT_STARTED`
- **Target behavior:** A separate runtime projection consumes `matching.events` and builds
  rebuildable last-trade and top-five order-book views. It is not the offline Market Reference
  builder.
- **Current evidence:** Only target documentation and dormant/legacy contracts exist.
- **Missing behavior:** Projection service, event mapping, Redis/PostgreSQL projection strategy,
  replay, gap handling, market-data topic/streaming contract, and tests.
- **Acceptance criteria:** Projection failure does not affect Matching, permanent trade storage,
  Account, QuickFIX, or admission. Delayed retry/DLQ is allowed because the view can be rebuilt.
- **Blocking dependencies:** ME-3.
- **GitHub issue:** [#133](https://github.com/WenHsuanYu/SimpleMatch/issues/133).

### QS-1: Build the required Query capability and Redis read models

- **Current status:** `NOT_STARTED`
- **Target behavior:** A required Phase 1 `query-service` exposes read-only order, execution,
  account-summary, and active-market-reference views from query-owned PostgreSQL and Redis
  projections. It is non-critical to trading admission but not optional for release completion.
- **Current evidence:** Target CQRS and transaction policies describe the read path, but no
  query-service source, Flyway schema, Redis key contract, projection consumer, or API exists.
- **Missing behavior:** Service scaffold, versioned APIs, query-owned PostgreSQL projections and
  inbox/checkpoints, Redis schema, Redis-first reads with PostgreSQL fallback, Account lifecycle and
  Matching Event projection inputs, active artifact view, freshness metadata, replay/rebuild, and
  outage tests.
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
- **Current evidence:** A process-local `GatewayAdmissionGate`, startup/readiness seams, and K8s
  configuration adapter exist. The gate starts open, has no operator command surface, and has no
  complete Matching/consumer monitor.
- **Missing behavior:** State machine, CLI/operator boundary, automatic close, Matching Fleet
  monitor, Risk/consumer/Kafka status adapters, unified `TradingSystemStatus`, exact open checks,
  warning/pause/interrupt policy, and certification tests.
- **Acceptance criteria:** `open` verifies Risk, 15 Matching owners, identical day/artifact/schema/
  algorithm versions, recovery lag zero for three checks, no quarantine, and critical-consumer
  readiness. Status silence over five seconds pauses new orders. Oldest unprocessed critical event
  warns at 30 seconds and pauses at 120 seconds. Identity or artifact inconsistency interrupts the
  market. Recovery never auto-reopens. Zero market activity remains Ready.
- **Blocking dependencies:** RM-1, ME-2, PS-1, AC-1, FG-1, and KD-1.
- **GitHub issue:** [#135](https://github.com/WenHsuanYu/SimpleMatch/issues/135).

### KD-1: Deploy and fence the fixed Matching fleet

- **Current status:** `NOT_STARTED`
- **Target behavior:** A 15-replica StatefulSet maps pod ordinal directly to partition. Each pod has
  a `ReadWriteOncePod` PVC and a per-partition Kubernetes Lease, and receives the artifact through
  the accepted ConfigMap or OCI path.
- **Current evidence:** No Matching Kubernetes manifest exists. QuickFIX StatefulSet and
  configuration RBAC are only reusable examples.
- **Missing behavior:** StatefulSet, Services, ConfigMap/OCI mounting, PVC template, Lease RBAC and
  adapter, `PartitionOwnershipPermit`, CPU requests/limits/affinity, probes, PodDisruptionBudget,
  and no-force-delete runbook.
- **Acceptance criteria:** `matching-N` cannot poll, replay, match, publish, or become Ready without
  its partition permit. Lease uncertainty for five seconds self-fences the runtime. Replacement
  waits for storage and Lease ownership, replays, and reaches Ready before operator reopen. The
  production profile requests three dedicated CPUs per pod and requires CPU Manager static-policy
  certification.
- **Blocking dependencies:** ME-1 and ME-2.
- **GitHub issue:** [#134](https://github.com/WenHsuanYu/SimpleMatch/issues/134).

### PD-1: Harden the cross-service deployment and security baseline

- **Current status:** `PARTIAL`
- **Target behavior:** Every Phase 1 Java workload and retained connector uses reusable Kubernetes
  bases/overlays, service-owned migration and CDC jobs, authenticated encrypted transport,
  least-privilege policy, business-role readiness, and auditable telemetry. Matching-specific
  ownership and fencing remain in KD-1.
- **Current evidence:** QuickFIX and Risk have partial raw manifests and shared typed configuration;
  the repository does not have one complete cross-service production overlay, security, Flyway Job,
  connector, NetworkPolicy, probe, and telemetry contract.
- **Missing behavior:** Complete service overlays, ConfigMap/Secret ownership, transport policy,
  service-scoped Flyway Jobs, retained Debezium deployments, RBAC/NetworkPolicy, status/probes,
  OpenTelemetry/log/metric policy, manifest validation, and dependency-outage smoke tests.
- **Acceptance criteria:** Required secrets and staging/production security fail closed. Applications
  do not migrate at startup. Connectors can reach only their owning outboxes. Liveness represents
  process health; readiness represents business-role availability. Logs expose no secrets, complete
  account payload, or raw FIX payload by default.
- **Blocking dependencies:** KC-1, RM-1, PS-1, AC-1, AR-1, FG-1, MD-1, QS-1, GO-1, and KD-1.
- **GitHub issue:** [#138](https://github.com/WenHsuanYu/SimpleMatch/issues/138).

### KC-1: Provision durable Matching Kafka topics

- **Current status:** `NOT_STARTED`
- **Target behavior:** Repository-managed infrastructure provisions `matching.commands` and
  `matching.events` with 15 partitions, replication factor 3, minimum ISR 2, delete-only cleanup,
  30-calendar-day retention, disabled unclean leader election, and disabled automatic topic
  creation. Producers use `acks=all` and idempotence.
- **Current evidence:** Configuration and Debezium templates reference the legacy topics, but no
  executable topic provisioning manifest defines the accepted production profile.
- **Missing behavior:** Provisioning, validation script, capacity calculation, disk/retention
  alerts, producer/consumer configuration, local single-broker override, and failure tests.
- **Acceptance criteria:** Production readiness fails if partition count or durability settings
  differ. Neither topic is compacted. Thirty days of the certified workload fit with operational
  headroom. Local replication factor 1 cannot pass production certification.
- **Blocking dependencies:** Kafka deployment/environment ownership.
- **GitHub issue:** [#125](https://github.com/WenHsuanYu/SimpleMatch/issues/125).

### PC-1: Certify capacity, latency, and recovery

- **Current status:** `NOT_STARTED`
- **Target behavior:** A reproducible benchmark fixes hardware, CPU affinity, wait strategy,
  150-book distribution, workload mix/depth/rate, warmup, and measurement definitions.
- **Current evidence:** Native CTest covers only current ingress behavior; no production workload or
  recovery benchmark exists.
- **Missing behavior:** Capacity model, input/output ring sizes, active-order limits, event fan-out
  limits, throughput/latency harness, soak tests, broker-outage tests, deterministic replay checksum,
  and 15-pod deployment certification.
- **Acceptance criteria:** Report core and Kafka end-to-end p50/p99/p99.9/max, RSS, ring occupancy,
  commands/events per second, and zero-loss recovery. Engine replay reaches lag zero within 60
  seconds after Lease/baseline/Kafka availability; total replacement target is 120 seconds. If
  full-day replay misses 60 seconds, open a separate snapshot design issue. No microsecond-level
  production claim is made before this gate passes.
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

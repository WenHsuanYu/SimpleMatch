# Taiwan Event-Driven Refactor Plan

## Status

- Design interview: complete
- Shared implementation brief: confirmed
- Production implementation: in progress; earlier phases provide reusable foundations, while the
  accepted offline artifact, native Matching runtime, durable downstream path, and operational
  admission remain incomplete
- Delivery model: incremental, test-first, and documentation-aligned

This plan describes both refactoring of existing modules and creation of target capabilities that
are documented but not yet present in the repository. New capabilities are labeled explicitly so
they are not mistaken for behavior that already exists.

The acceptance-criteria checklists below are final-program gates. The canonical detailed status for
the complete release frontier is
[Phase 1 Trading Release remaining work](routing-policy-remaining-work.md); older completed
runtime-publication steps below are historical evidence, not the accepted target.

## Phase 1 Trading Release Boundary

The **Phase 1 Trading Release** is the first complete pre-release trading-system boundary. It is not
the numbered refactor phase “Phase 1: Consolidate build and dependency policy.” Its canonical scope
is defined in
[`system-boundaries.md`](../services/docs/architecture/system-boundaries.md#phase-1-trading-release-boundary);
this plan owns sequencing, transaction criteria, rollback, and phase gates.

The release supports every eligible XTAI and ROCO regular-board common stock during continuous
trading, TWD only, and all six limit/market plus ROD/IOC/FOK combinations. Completion requires the
daily Market Reference Artifact, FIX/Risk/Account admission, deterministic Matching, permanent
trades/fills, critical Account and QuickFIX delivery, market-data streaming, the read-only query
service and Redis read models, operational admission, Kubernetes deployment/security, certification,
and pre-release compatibility cleanup. A non-critical component may remain outside admission
readiness without being optional for release completion.

The exclusions in this document's [Out of Scope](#out-of-scope) section bound this release.

## Historical Implementation Snapshot (2026-08-11)

This table is a frozen plan snapshot from 2026-08-11. It is retained to explain the original phase
sequence and does not describe the current source tree. For current status, use the
[Phase 1 Trading Release remaining-work inventory](routing-policy-remaining-work.md), which is
updated after source-aligned verification. The phase-15 checklist below records the later cleanup
commit boundaries but does not by itself certify a deployment.

Status as of 2026-08-11, reconciled against the source tree, GitHub issues, and the
remaining issue graph:

| Phase | Status | Current evidence and remaining boundary |
| --- | --- | --- |
| 0. Trustworthy baseline | Complete | Characterization, compatibility inventory, and baseline evidence are present. |
| 1. Build and dependency policy | Complete | Convention plugins, dependency policy, locking, and blocking quality gates are in place. |
| 2. Spring configuration | Complete | Services bind capability-scoped Spring properties; the shared platform facade and custom loader have been removed. |
| 3. v2 domain contracts | Complete | Typed v2 contracts and strict v1 compatibility adapters exist; live v1 seams remain intentionally transitional. |
| 4. Typed V1 schemas | Complete | Account, risk, and persistence use reset typed V1 Flyway schemas with migration verification. |
| 5. Market Reference | Partial / superseded runtime | Pure snapshot, tick, calendar, eligibility, routing, and codec foundations exist. The runtime Spring/PostgreSQL/outbox/Kafka design is obsolete; the offline official-source artifact builder is not complete. |
| 6. Account reservation authority | Complete | Reservation, rejection, fill, release, idempotency, concurrency, and lifecycle outbox behavior are implemented. |
| 7. Durable Risk Admission | Complete | Pending-before-remote-call admission, terminal transactions, recovery, backpressure, v2 gRPC, and v1 compatibility are implemented. |
| 8. QuickFIX admission and sessions | Partial | Durable ingress, typed Risk submission, FIX mapping, WAL recovery, and admission-gate foundations exist. Target remains one Gateway, explicit operator states, critical durable `matching.events` delivery, and JDBC MessageStore. |
| 9. Binary outbox CDC and Kafka | Partial / reusable foundation | Risk and Account delivery foundations are reusable. Runtime Market Reference CDC is obsolete, Matching will publish directly, and the two accepted Matching topics are not provisioned. |
| 10. C++ matching engine | Partial | CMake plus tested routing/quarantine ingress state machines exist. Kafka runtime, rings, order books, LMAX-style single-writer core, replay, barriers, and publisher do not. |
| 11. Account lifecycle integration | Partial | Idempotent account transitions, inbox, quarantine, and a critical v1 consumer exist; the final maker/taker Matching Event contract and raw-payload hash cutover remain. |
| 12. Durable and Redis projections | Foundation only | Persistence has only its application/Flyway baseline; #130 and #137 own the missing permanent trades/fills, query-owned PostgreSQL/Redis projections, and required query service. |
| 13. Market-data streaming | Not started | Neither the runtime projection pipeline nor the market-data streamer exists. |
| 14. Kubernetes and security | Partial | QuickFIX/Risk scaffolding exists. #134 owns Matching-specific deployment/fencing; #138 owns the missing cross-service overlays, transport/security, migration/CDC jobs, network policy, probes, telemetry, and smoke gates. |
| 15. Transition cleanup | Partial | Local commits complete #120's dead QuickFIX publisher removal. #139 must replace the production Account v1 RPC before #119 removes it; runtime Market Reference, legacy topics/contracts, and other coordinated cutovers remain. |

### Active Implementation Frontier

- GitHub Issue state, native sub-issue hierarchy, and dependency edges under
  [#10](https://github.com/WenHsuanYu/SimpleMatch/issues/10) are the executable task source of
  truth. This document owns phase/transaction/rollback gates, not assignment or closure state.
- [The canonical remaining-work inventory](routing-policy-remaining-work.md) owns capability status,
  repository evidence, and acceptance-gate mapping.
- [#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119) remains the pre-release cleanup
  tracker. [#120](https://github.com/WenHsuanYu/SimpleMatch/issues/120) is locally implemented but
  remains open remotely until delivery is completed.
- [#121](https://github.com/WenHsuanYu/SimpleMatch/issues/121)-[#124](https://github.com/WenHsuanYu/SimpleMatch/issues/124)
  own the offline artifact path; [#125](https://github.com/WenHsuanYu/SimpleMatch/issues/125)-[#129](https://github.com/WenHsuanYu/SimpleMatch/issues/129)
  own Kafka/Risk/native Matching; [#130](https://github.com/WenHsuanYu/SimpleMatch/issues/130)-[#133](https://github.com/WenHsuanYu/SimpleMatch/issues/133)
  own downstream consumers; [#134](https://github.com/WenHsuanYu/SimpleMatch/issues/134)-[#136](https://github.com/WenHsuanYu/SimpleMatch/issues/136)
  own deployment, operations, and certification.
- [#137](https://github.com/WenHsuanYu/SimpleMatch/issues/137) owns the required query service/read
  models; [#138](https://github.com/WenHsuanYu/SimpleMatch/issues/138) owns cross-service
  deployment/security; and [#139](https://github.com/WenHsuanYu/SimpleMatch/issues/139) owns the
  Account reservation v2 RPC cutover required by #119.
- The earlier #77-#86 Routing Policy chain and closed #87/#93-#99 delivery chain are historical
  evidence. [#92](https://github.com/WenHsuanYu/SimpleMatch/issues/92) remains the retained
  Risk/Account CDC foundation. Their runtime Market Reference publication model is superseded by
  ADR 0008.

## Problem Statement

SimpleMatch has an intended polyglot, event-driven architecture. Phases 0 through 7 have corrected
several original gaps, but the end-to-end trading path remains incomplete:

- Configuration now uses capability-scoped Spring property binding; deployment-level Secret,
  overlay, and restart policy still requires completion.
- Account, risk, and persistence now use clean business-typed V1 schemas; future schema changes must
  continue through service-owned Flyway migrations.
- Order fields cross boundaries as strings and are later stored in broad numeric or text columns.
- Typed v2 contracts exist, while active v1 compatibility seams still carry some string values.
- Risk Admission and Account Authority now own durable, idempotent local outcomes, while later
  matching and consumer integrations remain incomplete.
- Kafka, Debezium, outbox, retry, ordering, duplicate handling, and recovery policies need one
  complete cross-service contract and operational proof.
- Taiwan market rules, market-reference authority, and session behavior are not implemented end to
  end.
- Redis is planned but not implemented as a read model.
- A runtime Market Reference publisher exists but is now a removal target; only its pure
  normalization and validation logic is reusable in the offline builder.
- The native Matching module contains only routing/quarantine ingress foundations; the LMAX-style
  order-book runtime, market-data streamer, and query service are not complete.

Because SimpleMatch has no production release or external consumers, backward compatibility is not
a target constraint. Temporary adapters may keep intermediate commits reviewable, but the final
pre-release architecture removes superseded runtime paths, shallow interfaces, and legacy topics
instead of preserving them indefinitely.

## Acceptance Criteria

### Repository and compatibility

- [ ] The intended current worktree is reviewed and checkpointed before the refactor begins.
- [ ] Every commit leaves the repository buildable and its affected module tests passing.
- [ ] FIX 4.4 remains the client protocol while its internal routing and delivery implementation is
  replaced.
- [ ] Temporary v1 adapters may support safe intermediate commits, but no external backward-
  compatibility promise blocks the coordinated in-repository cutover.
- [ ] Compatibility adapters are removed before the first public release.
- [ ] Target architecture documentation and implementation-progress tracking remain separate.

### Configuration

- [ ] Spring Environment is the only runtime configuration authority.
- [ ] Typed configuration binding validates required values during startup.
- [ ] Exactly one environment profile is active: local, test, staging, or production.
- [ ] Kubernetes is treated as a deployment platform, not another environment profile.
- [ ] Configuration precedence matches Spring Boot behavior and is covered by tests.
- [ ] ConfigMaps and Secrets have disjoint key ownership.
- [ ] Sensitive values never appear in committed YAML, ConfigMaps, fixtures, or logs.
- [ ] Staging and production configuration changes require a controlled rolling restart.
- [ ] Missing required configuration fails startup with a useful diagnostic.

### Taiwan market model

- [ ] The Phase 1 Trading Release supports XTAI and ROCO regular-board listed common stocks during
  continuous trading.
- [ ] The Phase 1 Trading Release supports all six combinations of limit or market price with ROD,
  IOC, or FOK.
- [ ] TWD is the only Phase 1 Trading Release currency.
- [ ] Absolute timestamps are UTC instants; trading dates and session rules use Asia/Taipei.
- [ ] Market calendars, holidays, trading sessions, instruments, board-lot sizes, tick sizes, price
  limits, eligibility, and stable routing come from one approved daily Market Reference Artifact.
- [ ] Order-critical modules load the same active snapshot before becoming ready.
- [ ] Missing or stale market-reference data fails closed.
- [ ] Exceptional securities and unsupported sessions are rejected with stable reason codes.
- [ ] New orders are rejected outside continuous trading. During an in-session
  `NEW_ORDERS_PAUSED` state, cancellations remain accepted and durable; PRE_OPEN, interruption, and
  final close follow their stricter state rules.
- [ ] Remaining ROD orders expire at the supported session boundary.
- [ ] IOC may partially fill and cancels its remainder.
- [ ] FOK either fills completely or cancels without any fill.
- [ ] Market ROD follows Taiwan market-order priority and converted-reference price rules.
- [ ] Intraday volatility interruption pauses new-order admission until auction behavior is
  implemented.

### Data model

- [ ] Each service has one clean, final V1 Flyway migration for an empty schema.
- [ ] Old development migration chains remain recoverable from Git history or a pre-reset tag, not
  from active migration directories.
- [ ] Flyway does not silently baseline unexpectedly non-empty schemas.
- [ ] Services own schemas and credentials; there are no cross-service foreign keys or direct joins.
- [ ] Internal identifiers use UUIDv7 semantics, Java UUID, and PostgreSQL UUID.
- [ ] FIX business identity remains sender, target, trading day, and client order ID.
- [ ] Prices use signed 64-bit fixed-point values in 1/10,000 TWD units.
- [ ] Quantities use signed 64-bit share counts.
- [ ] TWD notionals and reservations use signed 64-bit fixed-point values in 1/10,000 TWD units.
- [ ] Required business values are explicit and non-null.
- [ ] Status-like values use bounded text plus check constraints, never numeric enum ordinals.
- [ ] JSONB is limited to genuinely variable diagnostic or projection metadata.
- [ ] Every non-constraint index is justified by a named query or operational scan.
- [ ] Trading and audit facts are immutable; physical cleanup is limited to disposable operational
  data.

### Event-driven processing

- [ ] PostgreSQL state remains authoritative for account, risk, idempotency, and durable
  projections.
- [ ] Commands and events are distinct Protobuf contracts.
- [ ] Every Matching Command/Event has the agreed metadata envelope, deterministic identity, and
  session-pinned schema/identity version.
- [ ] Where a Java business service uses an outbox, state changes and outbox inserts commit in one
  local transaction.
- [ ] Debezium captures only retained Java-service outbox tables; offline Market Reference and
  Matching do not use outbox publication.
- [ ] Outbox payloads contain complete serialized Protobuf envelopes as binary data.
- [ ] Kafka delivery is treated as at least once.
- [ ] Database-writing consumers record inbox deduplication and business changes in one transaction.
- [ ] Ordering is guaranteed only within the relevant domain stream.
- [ ] Risk publishes each Matching Command to the explicit artifact-assigned partition; Matching
  Events remain on the same numeric partition; account-originated events partition by account.
- [ ] Critical consumers preserve partition order during retries and quarantine rather than skip
  poison events.
- [ ] Non-critical projections may use delayed retry and dead-letter topics.
- [ ] Business rejection is a domain outcome, never a dead-letter event.
- [ ] Kafka is not the sole permanent audit archive.
- [ ] Event-delivery backlog is bounded by an admission backpressure policy.
- [ ] Delayed commands never execute in a later trading session.

### Transaction ownership and consistency

- [ ] Every affected phase follows the canonical
  [Cross-Cutting Transaction and Consistency Policy](cross-cutting-transaction-and-consistency-policy.md)
  (TP-1 through TP-12).
- [ ] An externally invoked public concrete application-service method uses
  `@Transactional` by default for each all-local business outcome.
- [ ] `TransactionTemplate` is used only for deliberately narrow, database-dependent critical
  sections; state-independent validation, expensive computation or serialization, file I/O, and
  remote calls remain outside.
- [ ] Repositories do not own cross-repository business transactions.
- [ ] A remote side effect uses an explicit outbox, idempotency, compensation, reconciliation, or
  persisted-intent/saga design.
- [ ] Every phase that changes a persisted consistency boundary defines its
  `Transaction Acceptance Criteria` before implementation and passes its mapped PostgreSQL-backed
  integration tests before its phase gate is complete.

### Admission and account consistency

- [ ] FIX gateway to risk admission remains synchronous.
- [ ] Risk durably records pending reservation work before calling account.
- [ ] Account is the sole authority for cash, positions, limits, and reservations.
- [ ] Reserve operations are deterministic and idempotent.
- [ ] Risk finalizes admission and its outbox only after reservation succeeds.
- [ ] Recovery resumes incomplete admission sagas after crashes and timeouts.
- [ ] Matching receives orders only after completed admission.
- [ ] Account consumes lifecycle events idempotently to settle or release reservations.
- [ ] Concurrent account mutations cannot over-reserve cash or positions.
- [ ] Idempotency records live at least as long as corresponding order and audit history.

### Redis and query paths

- [ ] Redis contains rebuildable, eventually consistent projections only.
- [ ] Redis failure cannot stop admission, reservation, or matching.
- [ ] Projection responses expose freshness metadata where required.
- [ ] Redis misses and outages fall back to PostgreSQL projections.
- [ ] Redis projections can be rebuilt through event replay.
- [ ] Query handling never scans Kafka.
- [ ] Market-data streaming uses a Redis snapshot followed by ordered Kafka deltas and
  resynchronizes on sequence gaps.

### Operations and quality

- [ ] Staging and production require authenticated encrypted PostgreSQL, Kafka, and gRPC
  connections.
- [ ] Insecure transport is allowed only by explicit local or test policy.
- [ ] Liveness reports process health; readiness reports ability to perform the service's required
  business role.
- [ ] Structured logs and OpenTelemetry context cross gRPC and Kafka boundaries.
- [ ] Logs never expose secrets, full account data, or raw FIX payloads by default.
- [ ] Metrics cover admission, reservation, outbox, CDC, consumer lag, retries, duplicates, sequence
  gaps, and quarantined partitions.
- [ ] Kubernetes resources use reusable bases and environment overlays.
- [ ] PostgreSQL and Kafka remain externally managed staging and production dependencies.
- [ ] Flyway migrations execute through deployment jobs, not application startup.
- [ ] Java static analysis, QuickFIX certification, C++ tests, schema tests, contract checks, and
  deployment validation pass.

## Solution

The solution is an incremental vertical-slice migration.

Existing Java services remain Spring Boot applications. Spring Cloud is used only where it adds
concrete platform value:
Kubernetes configuration integration and compatible dependency management. Kubernetes Service DNS
remains the discovery mechanism. Existing gRPC and FIX seams remain in place.

The first complete behavioral slice makes the limit-ROD order path correct end to end. It combines
typed contracts, the offline daily Market Reference Artifact, account reservation, durable Risk
admission, `matching.commands`, native deterministic Matching, `matching.events`, permanent trade
storage, Account/FIX critical consumption, and operational admission. Later slices add the remaining
order conditions, read models, and streaming through the same interfaces.

Deep modules concentrate policy:

- A configuration-resolution module binds and validates Spring properties.
- An offline market-reference builder owns official-source acquisition, normalization, stable
  routing, artifact validation, and approval evidence.
- An account-reservation module owns funds and position authority.
- A durable-admission module owns idempotency, saga state, final outcome, and outbox atomicity.
- A FIX-admission module owns protocol normalization, recovery, and response projection.
- A matching module owns deterministic book state and Taiwan execution rules.
- Projection modules own idempotent PostgreSQL and Redis read models.

New deployable capabilities are added only after their upstream interfaces are stable. The current
runtime Market Reference publisher is migrated to an offline tool and removed; remaining deployable
  capabilities include the complete C++ Matching runtime, durable consumers, market-data streamer,
  and required query service.

## Commit Plan

Each item below is intended to be one small commit unless its acceptance test shows it must be split
further. Every commit runs the narrowest relevant tests before the broader phase gate.

The required `Transaction Acceptance Criteria` sections below reference the
canonical [Cross-Cutting Transaction and Consistency Policy](cross-cutting-transaction-and-consistency-policy.md).
They are a readiness gate: criteria must be complete before a phase begins, and the mapped
PostgreSQL-backed integration tests must pass before a phase is complete. Run
`bash scripts/check-transaction-acceptance-criteria.sh` to verify the documentation structure; it
does not replace behavioral review or tests.

### Phase 0: Establish a trustworthy baseline

- [x] Commit 0.1: Review the current dirty worktree, classify intended source changes versus
  generated or runtime artifacts, and checkpoint only intended work.
- [x] Commit 0.2: Record the current module inventory and label documented but missing deployables
  as target capabilities.
- [x] Commit 0.3: Add black-box characterization tests for the current FIX new order, cancellation,
  risk response, durable submission, and outbox behavior.
- [x] Commit 0.4: Add a machine-checked compatibility inventory for current v1 Protobuf messages and
  field numbers.
- [x] Commit 0.5: Record the passing baseline validation results and known environment-only
  blockers.

Phase gate:

- [x] Current behavior is characterized.
- [x] No target capability is described as already implemented.
- [x] The baseline commit is recoverable.

Rollback:

- Revert only the characterization commits; no production behavior has changed.

### Phase 1: Consolidate build and dependency policy

- [x] Commit 1.1: Move shared library and plugin versions into the version catalog without changing
  resolved versions.
- [x] Commit 1.2: Add a Spring service convention module and migrate one no-behavior-change service
  as proof.
- [x] Commit 1.3: Migrate the remaining Spring services to the convention module one at a time.
- [x] Commit 1.4: Add a Protobuf convention module and migrate contract generation without changing
  generated interfaces.
- [x] Commit 1.5: Deepen the existing Flyway convention around service identity, schema, migration
  location, and validation tasks.
- [x] Commit 1.6: Remove root build path predicates and duplicated dependency declarations made
  obsolete by conventions.
- [x] Commit 1.7: Add dependency locking or verification appropriate to the repository's release
  workflow.

Phase gate:

- [x] Dependency resolution is unchanged except for explicitly documented corrections.
- [x] All Java tests and static analysis pass.
- [x] Flyway task discovery remains intact.

Rollback:

- Revert convention migrations service by service; module behavior is unchanged.

### Phase 2: Make Spring configuration authoritative

- [x] Commit 2.1: Add tests for base YAML, profile YAML, environment overrides, and test-only
  override precedence.
- [x] Commit 2.2: Add service-scoped typed configuration objects with startup validation while
  retaining the existing compatibility facade.
- [x] Commit 2.3: Bind the compatibility facade from Spring Environment instead of independent file
  discovery.
- [x] Commit 2.4: Add environment-profile exclusivity and staging or production security-policy
  validation.
- [x] Commit 2.5: Add Kubernetes ConfigMap and Secret imports with disjoint key validation.
- [x] Commit 2.6: Add fail-fast behavior for missing required Kubernetes configuration.
- [x] Commit 2.7: Remove custom environment alias resolution after every caller uses typed
  properties.
- [x] Commit 2.8: Remove the custom loader after compatibility tests prove it is unused.
- [x] Commit 2.9: Document the configuration matrix, precedence, secret ownership, and restart
  policy.

Phase gate:

- [x] The same property names bind in local, test, staging, and production.
- [x] ConfigMap and Secret conflicts fail validation.
- [x] Sensitive values are absent from committed configuration.
- [x] Each Spring application starts under local and test profiles.

Rollback:

- Keep the compatibility facade and revert one binding group at a time.

### Phase 3: Introduce v2 domain contracts

- [x] Commit 3.1: Add the common v2 event metadata envelope and schema compatibility checks.
- [x] Commit 3.2: Add typed UUID-backed identifiers and validation rules.
- [x] Commit 3.3: Add fixed-point price, TWD notional, and share-quantity contracts.
- [x] Commit 3.4: Add instrument identity, venue MIC, trading day, snapshot ID, and session-state
  contracts.
- [x] Commit 3.5: Add v2 new-order and cancel commands.
- [x] Commit 3.6: Add v2 admission outcome events.
- [x] Commit 3.7: Add v2 reservation commands and account lifecycle events.
- [x] Commit 3.8: Add v2 matching lifecycle and execution events.
- [x] Commit 3.9: Add v1-to-v2 ingress adapters with round-trip compatibility tests.
- [x] Commit 3.10: Add stable rejection and cancellation reason catalogs.

Phase gate:

- [x] Field numbers are never reused.
- [x] Invalid UUID, price, quantity, currency, and timestamp values fail at the intended seam.
- [x] Existing v1 behavior remains available through adapters.

Rollback:

- v2 is additive; revert consumers independently while retaining v1.

### Phase 4: Reset Flyway histories into typed V1 schemas

- [x] Commit 4.1: Add a reviewed data dictionary containing business meaning, units, ranges,
  nullability, constraints, and query ownership.
- [x] Commit 4.2: Tag or otherwise checkpoint the old migration histories before active scripts are
  replaced.
- [x] Commit 4.3: Replace the account migration chain with one typed V1 schema and clean-install
  migration test.
- [x] Commit 4.4: Replace the risk migration chain with one typed V1 schema and clean-install
  migration test.
- [x] Commit 4.5: Replace the persistence migration chain with one typed V1 schema and clean-install
  migration test.
- [x] Commit 4.6: Add consistent inbox tables and uniqueness constraints to database-writing
  consumers.
- [x] Commit 4.7: Add the binary outbox table shape to event-originating services.
- [x] Commit 4.8: Replace legacy-upgrade tests with empty-schema and invariant tests.
- [x] Commit 4.9: Disable permissive baseline-on-migrate behavior for ordinary clean installations.
- [x] Commit 4.10: Update Flyway smoke checks and schema documentation for the reset.

Phase gate:

- [x] Every service migrates from an empty database.
- [x] Re-running migrate is a no-op.
- [x] Constraints reject invalid business values.
- [x] Repository queries have justified indexes and reviewed plans.

Rollback:

- Restore old migration directories from the checkpoint and recreate disposable development schemas.

### Phase 5: Create the market-reference publisher capability

> Historical/superseded target: the checked commits in this phase are verified implementation
> history, but ADR 0008
> supersedes their runtime service, persistence, activation, outbox, and Kafka publication model.
> Pure instrument/tick/calendar/eligibility/codec logic is migration input for the offline builder.
> The target artifact work is tracked in the canonical remaining-work inventory and must not be
> inferred complete from this phase gate.

- [x] Commit 5.1: Scaffold the documented market-data publisher as a Spring Boot service without
  runtime consumers.
- [x] Commit 5.2: Add immutable market snapshot types and fixture-based tests.
- [x] Commit 5.3: Add Taiwan trading-calendar and holiday resolution tests.
- [x] Commit 5.4: Add instrument identity, venue, board-lot, tick-table, and eligibility import
  validation.
- [x] Commit 5.5: Add daily reference-price and absolute price-limit validation.
- [x] Commit 5.6: Add snapshot persistence with source timestamp, checksum, and activation state.
- [x] Commit 5.7: Add snapshot publication through the service outbox.
- [x] Commit 5.8: Add readiness behavior for missing, stale, or invalid daily snapshots.
- [x] Commit 5.9: Add deterministic replay and simulator adapters for local and test environments.

#### Transaction Acceptance Criteria

##### Applicable policy

TP-1 through TP-12 in the [canonical policy](cross-cutting-transaction-and-consistency-policy.md).

##### Transaction owner

The public `MarketSnapshotApplicationService.publishSnapshot` operation owns the all-local
publication transaction and uses `@Transactional` by default.

##### Atomic writes

Snapshot version, metadata, complete contents or their immutable reference, activation state,
publication metadata, and the snapshot-published outbox record commit or roll back together.

##### Work outside the transaction

Source parsing, schema and static-field validation, tick and trading-unit normalization,
deterministic snapshot construction, and serialization independent of generated values occur before
the transaction.

##### Work inside the transaction

Current-version validation, version allocation, active-snapshot conflict checks, snapshot
persistence and activation, and any final envelope dependent on the persisted version remain inside.

##### Failure outcome

No new active snapshot is visible if any snapshot or outbox write fails. No metadata, contents,
activation, or publication event may be partially committed.

##### Retry and idempotency

The same source identity and checksum return the existing publication result; changed content has an
explicit new-version outcome.

##### Concurrency control

A unique current-snapshot constraint plus version allocation prevents two active versions. A losing
publisher receives a deterministic conflict or re-reads the published result.

##### Timeout policy

The publication transaction has a 10-second timeout. The active-version lock query has a tighter
2-second JDBC timeout.

##### Verification

`MarketSnapshotPublicationTransactionIT` maps TP-12 through named cases for atomic commit, first and
later mutation rollback, outbox rollback, constraint rejection, concurrent activation conflict,
duplicate import, checked and unchecked rollback, and absence of partial state. Inbox completion and
consumer restart are N/A because this operation does not consume an event. The companion
`MarketSnapshotPublicationPostgresIT` runs the same Flyway migration and a durable snapshot/outbox
assertion against an explicitly supplied isolated PostgreSQL DSN.

Phase gate:

- [x] XTAI and ROCO fixtures produce deterministic snapshots.
- [x] Unsupported instruments carry explicit eligibility reasons.
- [x] No trading module calls an exchange website synchronously.

Rollback:

- The new service is additive and may remain undeployed.

### Phase 6: Deepen account reservation authority

- [x] Commit 6.1: Add tests for available cash, available positions, limits, and reservation
  invariants.
- [x] Commit 6.2: Introduce typed account, balance, position, limit, and reservation domain values.
- [x] Commit 6.3: Implement idempotent reserve behavior in one local transaction.
- [x] Commit 6.4: Add database-enforced account concurrency control.
- [x] Commit 6.5: Add reservation-created and reservation-rejected outbox events.
- [x] Commit 6.6: Add inbox-based execution-event deduplication.
- [x] Commit 6.7: Apply full and partial fills to authoritative account state.
- [x] Commit 6.8: Release remaining reservations for cancel, expiry, IOC remainder, and FOK
  cancellation.
- [x] Commit 6.9: Add administrative account and position provisioning for development and
  controlled environments.

#### Transaction Acceptance Criteria

##### Applicable policy

TP-1 through TP-12 in the [canonical policy](cross-cutting-transaction-and-consistency-policy.md).

##### Transaction owner

The public `AccountReservationApplicationService.reserve` and lifecycle event-processing operation
each own their all-local transaction and use
`@Transactional` by default.

##### Atomic writes

Inbox claim or deduplication, account and reservation mutation, account version, processed aggregate
sequence, lifecycle result, account outbox record, and inbox completion commit or roll back together
as one applicable processing outcome.

##### Work outside the transaction

Transport decoding, authentication, envelope-shape and static-field validation, and deterministic
calculations independent of account state occur before the transaction.

##### Work inside the transaction

Inbox duplicate checks, account load with concurrency control, available-funds or position
validation, reservation mutation, version and sequence checks, outbox creation, and inbox completion
remain inside.

##### Failure outcome

Infrastructure or outbox failure leaves the inbound event retryable without a reservation.
Insufficient funds may atomically persist a stable rejection and required outbox event, but never a
reservation.

##### Retry and idempotency

The same event ID never reserves, settles, releases, or adjusts twice. Stale or duplicate aggregate
sequences have an explicit no-op, duplicate, quarantine, or rejection outcome.

##### Concurrency control

Per-account optimistic versioning or conditional updates serialize authoritative mutation. A losing
concurrent reserve observes a conflict or retriable result; it cannot over-reserve.

##### Timeout policy

The service inherits its documented default transaction timeout; account-lock or conditional-update
contention uses a tighter documented timeout.

##### Verification

`AccountReservationApplicationServiceTransactionTest` maps the implemented authority slice through
H2/Flyway integration cases for atomic reserve and outbox writes, stable rejection, execution
deduplication, fill settlement, idempotent release, provisioning, position reservation, and
concurrent cash reservation. PostgreSQL Testcontainers remains the deployment-level follow-up for
engine-specific lock and isolation verification.

Phase gate:

- [x] Concurrent reserves cannot overspend cash or positions.
- [x] Duplicate reserve and lifecycle messages are harmless.
- [x] Account state and its outbox commit atomically.

Rollback:

- Keep the existing account interface behind an adapter until the new module passes concurrency and
  integration tests.

### Phase 7: Deepen durable risk admission

- [x] Commit 7.1: Add table-driven tests for transport-independent submission validation.
- [x] Commit 7.2: Extract the FIX business-identity and content-equivalence policy into one module.
- [x] Commit 7.3: Add tests for equivalent replay, conflicting replay, and concurrent duplicate
  submission.
- [x] Commit 7.4: Introduce a durable admission journal interface that owns saga state and local
  transaction boundaries.
- [x] Commit 7.5: Persist pending reservation state before external account calls.
- [x] Commit 7.6: Add the idempotent account reservation adapter.
- [x] Commit 7.7: Finalize accepted admission and binary outbox event atomically.
- [x] Commit 7.8: Finalize business rejection and binary outbox event atomically.
- [x] Commit 7.9: Add recovery of pending admissions after timeout or restart.
- [x] Commit 7.10: Add backpressure behavior based on CDC delivery lag.
- [x] Commit 7.11: Expose the deep admission interface through v2 gRPC.
- [x] Commit 7.12: Route v1 gRPC through the compatibility adapter.

#### Transaction Acceptance Criteria

##### Applicable policy

TP-1 through TP-12 in the [canonical policy](cross-cutting-transaction-and-consistency-policy.md).

##### Transaction owner

The public `OrderAdmissionApplicationService.beginAdmission` and
`finalizeAdmission` operations own their respective local transactions and use
`@Transactional` by default.

##### Atomic writes

For each local admission outcome, idempotency state, durable decision, order state and status,
aggregate sequence, validated rule or snapshot reference, reason, and matching admitted or rejected
outbox event commit or roll back together.

##### Work outside the transaction

Decode, authenticate, statically validate, calculate pure values, perform remote checks, and
serialize data independent of persisted identifiers before a local transaction.

##### Work inside the transaction

Duplicate detection, current-state checks, sequence allocation, order and decision persistence,
final dependent envelope construction, and idempotency result persistence remain inside.

##### Failure outcome

No accepted order lacks its admitted event, no event exists for an uncommitted order, and no
duplicate idempotency key creates another authoritative order.

##### Retry and idempotency

The persisted admission saga and idempotent account command recover remote success followed by local
failure. Equivalent retries reproduce the original outcome; conflicting retries receive a stable
conflict.

##### Concurrency control

A unique command or idempotency key and monotonic aggregate sequence allocation select one result. A
losing concurrent submitter reads that result or receives a stable conflict.

##### Timeout policy

The local transaction inherits the documented admission timeout. No transaction is held during a
remote call; pending-saga recovery has its own bounded timeout.

##### Verification

`OrderAdmissionApplicationServiceTransactionTest` maps the implemented saga through H2/Flyway cases
for pending-before-call, accepted/rejected atomic outbox finalization, equivalent replay, stable
conflict, and remote outage recovery. `CdcLagBackpressurePolicyTest` covers the lag gate, scheduled
pending recovery is enabled in the service configuration, and the v2 gRPC server binds alongside the
v1 service. The v1 compatibility adapter preserves the legacy transport seam.

Phase gate:

- [x] No database transaction remains open across a network call.
- [x] Every pending saga reaches a recoverable terminal state.
- [x] Equivalent retries return the same outcome and event identity.
- [x] Conflicting retries return a stable idempotency conflict.

Rollback:

- Retain the current admission adapter until the new journal has passed integration tests; switch
  wiring in a dedicated commit.

### Phase 8: Deepen the QuickFIX admission and session modules

- [x] Commit 8.1: Add FIX mapping tests for all v2 identifiers and fixed-point values.
- [x] Commit 8.2: Add mapping tests for all six price and time-in-force combinations.
- [x] Commit 8.3: Introduce a deep FIX-admission module around normalization, risk submission, WAL
  recovery, and FIX outcome projection.
- [x] Commit 8.4: Make gateway WAL replay resubmit unresolved work through the idempotent risk
  interface.
- [x] Commit 8.5: Prevent WAL replay from publishing matching commands directly.
- [x] Commit 8.6: Deepen the session directory around route, cancel context, lifecycle status, and
  execution deduplication.
- [x] Commit 8.7: Preserve explicit single-owner session assignment and reject conflicting
  ownership.
- [x] Commit 8.8: Add cancellation behavior during admission pauses and market interruptions.
- [ ] Commit 8.9: Update outbound execution and cancellation projection to v2 lifecycle events.
- [x] Commit 8.10: Extend QuickFIX certification for v1 compatibility and v2 internal behavior.

Phase gate:

- [x] Raw FIX remains adapter-level audit data.
- [x] Gateway replay cannot create duplicate admitted orders.
- [x] One active owner exists per FIX session.
- [x] Certification covers new, cancel, duplicate, and recovery paths.

Rollback:

- Keep v1 mapping and old wiring available until certification passes.

### Phase 9: Establish binary outbox CDC and Kafka policy

- [x] Commit 9.1: Configure Debezium Outbox Event Router for binary payload pass-through in local
  infrastructure.
- [x] Commit 9.2: Restrict each connector to its service outbox table.
- [x] Commit 9.3: Add domain-stream topic naming and partition-key tests.
- [x] Commit 9.4: Add outbox-to-Kafka integration tests for exact payload bytes, keys, headers,
  timestamps, and duplicates.
- [x] Commit 9.5: Add schema compatibility validation to continuous integration.
- [x] Commit 9.6: Add ordered in-place retry and partition quarantine for critical consumers.
- [x] Commit 9.7: Add delayed retry and dead-letter handling for non-critical projections.
- [x] Commit 9.8: Add Debezium lag, outbox age, consumer lag, duplicate, and quarantine metrics.
- [x] Commit 9.9: Add bounded outbox cleanup after the configured CDC safety window.

Phase gate:

- [x] Database commits survive Kafka and connector outages.
- [x] Replayed or duplicated events do not duplicate state changes.
- [x] Critical records never overtake a failed earlier record in the partition.

Phase 9 evidence executed on 2026-08-04:

- `bash scripts/verify-outbox-connector-contracts.sh` validates owner table scope, binary payload
  pass-through, headers, timestamps, and explicit partitions for Risk, Account, and Market Reference.
- `bash scripts/run-outbox-cdc-contract-check.sh` runs PostgreSQL, Kafka, and Kafka Connect in
  Docker and verifies exact bytes, keys, headers, timestamps, partitions, pause/resume retention,
  and connector recovery.
- Shared delivery tests, all Java service tests, QuickFIX certification, and the blocking
  `./gradlew -q staticAnalysis` pass. The native CMake/CTest suite passes all nine ingress tests.
- `MicrometerDeliveryMetrics` exposes stable delivery outcome and operational-observation labels;
  `OutboxRetentionPolicy` authorizes cleanup only after a durable CDC watermark and replay or
  investigation retention boundary. No cleanup job deletes rows without those watermarks.

Rollback:

- Stop connectors and keep durable outbox rows; no business state rollback is required.

### Phase 10: Create the C++ matching engine capability

- [x] Commit 10.1: Scaffold native CMake targets and the initial deterministic routing/quarantine
  ingress tests.
- [ ] Commit 10.2: Add UUID, fixed-point price, share quantity, and instrument value types.
- [ ] Commit 10.3: Define `MatchingCommand` and `MatchingEvent` Protobuf contracts plus C++/Java
  golden raw-record fixtures.
- [ ] Commit 10.4: Add startup loading and exact identity validation for the mounted daily Market
  Reference Artifact.
- [ ] Commit 10.5: Add preallocated SPSC input/output rings and a no-I/O single-writer core harness.
- [ ] Commit 10.6: Implement deterministic price-time priority and limit-ROD order books through
  tests.
- [ ] Commit 10.7: Add IOC/FOK, market-order, cancel, remainder, and expiry behavior.
- [ ] Commit 10.8: Add stable command deduplication plus deterministic output/match indices and
  event/trade identities.
- [ ] Commit 10.9: Add direct Kafka `assign()` ingress for one configured partition and safe ring
  backpressure.
- [ ] Commit 10.10: Add direct idempotent `matching.events` publication and per-input output ACK
  tracking.
- [ ] Commit 10.11: Commit only the contiguous completed input watermark and cover every crash
  window with replay tests.
- [ ] Commit 10.12: Add Open/Close Barrier processing, PVC baseline metadata, Kafka fallback scan,
  state-only replay, and deterministic ROD expiry.
- [ ] Commit 10.13: Add partition ownership permits, Lease-loss self-fencing, readiness, quarantine,
  ring, lag, and recovery status.
- [ ] Commit 10.14: Add fixed-capacity and broker-outage tests, then the production benchmark and
  recovery certification harness.

Phase gate:

- [ ] Replaying the same ordered command stream produces identical outcomes.
- [ ] The same event identity always has the same exact Kafka record bytes.
- [ ] FOK never partially fills.
- [ ] IOC never rests.
- [ ] Market ROD follows the confirmed Taiwan behavior.
- [ ] Unknown artifact/schema/algorithm versions fail closed.
- [ ] No ring or order-capacity exhaustion drops, overwrites, or dynamically expands hot-path state.
- [ ] Full-day replay reaches lag zero within the accepted 60-second engine SLO or opens a separate
  snapshot design issue.

Rollback:

- Keep the engine undeployed until deterministic tests, Kafka integration, fixed-ownership fencing,
  and certification pass; admitted commands remain durable in Kafka.

### Phase 11: Complete account lifecycle integration

- [ ] Commit 11.1: Cut the critical Account consumer over to final maker/taker events from
  `matching.events`.
- [ ] Commit 11.2: Persist event ID plus SHA-256 of the exact Kafka record value bytes in the same
  transaction as account mutation.
- [ ] Commit 11.3: Enforce aggregate-sequence gap detection and quarantine.
- [ ] Commit 11.4: Add crash-recovery tests between database commit and Kafka acknowledgment.
- [ ] Commit 11.5: Publish account lifecycle outcomes through the account outbox.

#### Transaction Acceptance Criteria

##### Applicable policy

TP-1 through TP-12 in the [canonical policy](cross-cutting-transaction-and-consistency-policy.md).

##### Transaction owner

The public `AccountLifecycleEventProcessor.process` operation owns the local event-processing
transaction and uses
`@Transactional` by default.

##### Atomic writes

Inbox claim or deduplication, balance or position mutation, reservation settlement or release,
account version, aggregate sequence, lifecycle outcome, account outbox record, and inbox completion
commit or roll back together.

##### Work outside the transaction

Decode, validate the envelope and static fields, authenticate where applicable, and calculate
state-independent values before the transaction.

##### Work inside the transaction

Duplicate checks, account lock or version checks, lifecycle-transition validation, reservation
mutation, sequence handling, outbox creation, and inbox completion remain inside.

##### Failure outcome

Database, serialization, lock, or outbox failure leaves the event retryable and does not mark the
inbox complete. A valid business rejection persists only the defined durable rejection outcome.

##### Retry and idempotency

Duplicate events are no-ops or reproduce their stored result. Sequence gaps are quarantined without
advancing account state.

##### Concurrency control

Per-account optimistic versioning, a conditional update, or row locking prevents double settlement
or release. The losing delivery is retried, rejected, or quarantined explicitly.

##### Timeout policy

The processor inherits the documented account-service timeout and uses a tighter timeout for
account-lock contention.

##### Verification

Planned PostgreSQL Testcontainers test `AccountLifecycleTransactionIT` maps every TP-12 case through
named cases for atomic commit; first and later write rollback; outbox and inbox-completion rollback;
constraint; lock or version conflict; duplicate delivery; checked and unchecked rollback; restart
before acknowledgement; concurrent processing; and no partial account state.

Phase gate:

- [ ] Every terminal matching outcome settles or releases its reservation.
- [ ] Duplicate and replayed lifecycle events preserve exact account state.

Rollback:

- Pause the consumer at its committed offset; authoritative reservations remain queryable for
  reconciliation.

### Phase 12: Build durable projections and Redis read models

Executable ownership is split between
[#130](https://github.com/WenHsuanYu/SimpleMatch/issues/130) for permanent critical Persistence and
[#137](https://github.com/WenHsuanYu/SimpleMatch/issues/137) for the required Query capability.

- [ ] Commit 12.1: Add the critical Persistence consumer and idempotent PostgreSQL inbox, immutable
  `trades`, two `order_fills`, and order projections for `matching.events`.
- [ ] Commit 12.2: Add projection rebuild tests from retained event fixtures.
- [ ] Commit 12.3: Add a versioned Redis key schema for order and execution read models.
- [ ] Commit 12.4: Add idempotent Redis projection updates after durable PostgreSQL projection
  commits.
- [ ] Commit 12.5: Add Redis outage and PostgreSQL fallback behavior.
- [ ] Commit 12.6: Add Redis rebuild tooling and freshness metadata.
- [ ] Commit 12.7: Scaffold the documented read-only query service.
- [ ] Commit 12.8: Add Redis-first order and execution queries with PostgreSQL fallback.
- [ ] Commit 12.9: Add account-summary and active-market-snapshot queries.

#### Transaction Acceptance Criteria

##### Applicable policy

TP-1 through TP-12 in the [canonical policy](cross-cutting-transaction-and-consistency-policy.md).

##### Transaction owner

The public `ProjectionEventProcessor.process` operation owns the durable projection transaction and
uses
`@Transactional` by default.

##### Atomic writes

Inbox state, aggregate-sequence or projection-version check, PostgreSQL projection mutation,
checkpoint, any generated outbox record, and inbox completion commit or roll back together.

##### Work outside the transaction

Decode, static validation, replacement-payload preparation, and expensive Redis serialization occur
before the transaction. Redis publication is retried or rebuilt after the durable PostgreSQL commit.

##### Work inside the transaction

Duplicate detection, version and sequence checks, durable projection mutation, checkpoint movement,
generated outbox insertion, and inbox completion remain inside.

##### Failure outcome

The inbox cannot complete without its projection update, and a failed local transaction leaves the
event retryable. Redis cannot make a PostgreSQL update appear atomically published.

##### Retry and idempotency

Incremental updates never double-apply. Stale events are ignored, recorded, or quarantined; a gap
pauses or resynchronizes instead of silently advancing.

##### Concurrency control

Projection version or aggregate sequence conditional updates reject stale writers. The losing event
is ignored, retried, or quarantined according to its contract.

##### Timeout policy

The processor inherits the documented projection-service timeout; sequence or checkpoint locks use a
tighter documented timeout.

##### Verification

Planned PostgreSQL Testcontainers test `ProjectionEventTransactionIT` maps every TP-12 case through
named cases for atomic commit; first and later write rollback; generated-outbox and inbox-completion
rollback; constraint; conditional-update conflict; duplicates; checked and unchecked rollback;
restart; concurrent projection updates; and no partial state. Generated-outbox cases are N/A, with
that reason, for a projection that emits no event.

Phase gate:

- [ ] Redis can be deleted and rebuilt without business-state loss.
- [ ] Query responses disclose freshness where required.
- [ ] Query handling does not read Kafka synchronously.

Rollback:

- Disable Redis reads and use PostgreSQL projections until Redis is rebuilt.

### Phase 13: Create market-data projection and streaming capabilities

- [ ] Commit 13.1: Use an independent non-critical consumer group to convert `matching.events`
  trade and book-change facts into versioned `marketdata.events`.
- [ ] Commit 13.2: Build deterministic last-trade and top-five book projections.
- [ ] Commit 13.3: Persist market-data snapshots in Redis with sequence metadata.
- [ ] Commit 13.4: Scaffold the documented market-data streamer.
- [ ] Commit 13.5: Serve initial Redis snapshots before Kafka deltas.
- [ ] Commit 13.6: Detect sequence gaps and resynchronize instead of streaming inconsistent deltas.
- [ ] Commit 13.7: Add slow-consumer backpressure and disconnect policy.

#### Transaction Acceptance Criteria

##### Applicable policy

TP-1 through TP-12 in the [canonical policy](cross-cutting-transaction-and-consistency-policy.md),
including its bounded replay and recovery rules.

##### Transaction owner

The public `MarketDataProjectionApplicationService.process` operation owns each live durable update
with
`@Transactional`; `replayBatch` owns a bounded, deliberately narrow `TransactionTemplate` database
critical section after batch construction.

##### Atomic writes

For each live event or replay batch, durable projection changes, aggregate or stream sequence
progress, inbox or replay deduplication, recovery metadata, and any intentionally emitted outbox
record commit or roll back together. Redis snapshots and streaming occur after that durable outcome.

##### Work outside the transaction

Decode, validate, batch, serialize snapshots, write Redis, wait for Kafka acknowledgements, and
perform streaming I/O outside the database boundary.

##### Work inside the transaction

Sequence and gap checks, durable projection writes, bounded checkpoint movement, inbox or replay
deduplication, recovery metadata, and permitted outbox writes remain inside.

##### Failure outcome

A failed batch does not advance its checkpoint; recovery resumes from the last committed point.
Readers observe either the old complete projection or an atomically activated rebuilt projection,
never a partial mixture.

##### Retry and idempotency

Replay and live duplicate delivery are idempotent. Sequence gaps pause, quarantine, or
resynchronize; replay suppresses downstream publication unless a documented test proves it is
intentionally enabled.

##### Concurrency control

Per-instrument sequence conditional updates and an atomic shadow-projection cutover prevent stale or
concurrent writers from advancing a stream. A losing writer is retried, ignored as stale, or
quarantined explicitly.

##### Timeout policy

Live processing inherits the documented market-data timeout. Each replay batch has a tighter bounded
transaction timeout; a complete replay is never one transaction.

##### Verification

Planned PostgreSQL Testcontainers test `MarketDataReplayTransactionIT` maps every TP-12 case through
named cases for successful batch commit; first and later write rollback; permitted-outbox and inbox
rollback; constraints; conditional conflicts; duplicate replay; checked and unchecked rollback;
restart after partial work; concurrent stream updates; and no partial projection or checkpoint.
Inbox or outbox cases are N/A, with that reason, for a projection contract that does not use them.

Phase gate:

- [ ] Initial snapshot and subsequent deltas form one monotonic sequence.
- [ ] Redis loss causes resynchronization, not trading-path failure.
- [ ] Public depth is limited to last trade and best five levels.

Rollback:

- Disable streaming while retaining matching and durable projections.

### Phase 14: Add Kubernetes deployment and security policy

Matching-specific ownership/fencing is tracked by
[#134](https://github.com/WenHsuanYu/SimpleMatch/issues/134), Gateway operational admission by
[#135](https://github.com/WenHsuanYu/SimpleMatch/issues/135), and the cross-service production
baseline by [#138](https://github.com/WenHsuanYu/SimpleMatch/issues/138).

- [ ] Commit 14.1: Add a 15-replica Matching StatefulSet whose ordinal `0..14` is the configured
  Kafka partition.
- [ ] Commit 14.2: Give each Matching ordinal a `ReadWriteOncePod` PVC and per-partition Kubernetes
  Lease; expose only a valid `PartitionOwnershipPermit` to the domain boundary.
- [ ] Commit 14.3: Self-fence ingress, core, and publisher within five seconds of uncertain Lease
  renewal; disallow force deletion in the normal Matching restart runbook.
- [ ] Commit 14.4: Mount the reviewed daily artifact from an immutable ConfigMap, or from a
  digest-pinned OCI data image when it exceeds 900 KiB, at the same application path.
- [ ] Commit 14.5: Set production Matching pods to Guaranteed QoS with three CPUs each and document
  compatible CPU-manager and CSI prerequisites.
- [ ] Commit 14.6: Add one QuickFIX Gateway workload and its PRE_OPEN/OPEN/NEW_ORDERS_PAUSED/
  MARKET_INTERRUPTED/CLOSED operator configuration.
- [ ] Commit 14.7: Convert remaining workloads to reusable bases/overlays and add service-scoped
  Flyway jobs, retained Debezium connectors, Secrets, and least-privilege network policy.
- [ ] Commit 14.8: Add component status endpoints/adapters, liveness/readiness/startup probes,
  structured telemetry, metrics, and alerts.
- [ ] Commit 14.9: Validate manifests and run replacement, Lease-loss, artifact-mismatch, broker-
  outage, and deployment smoke tests.

Phase gate:

- [ ] Local, test, staging, and production overlays use the same property names.
- [ ] Missing Secrets fail startup.
- [ ] Applications do not run schema migrations at startup.
- [ ] Redis, Kafka, PostgreSQL, artifact, Matching ownership, and critical-consumer failures produce
  the agreed readiness behavior.
- [ ] Exactly one Ready Matching pod owns each partition and no pod can process without its permit.

Rollback:

- Roll back one workload overlay or image at a time; schema jobs remain independently auditable.

### Phase 15: Remove transition scaffolding

[#119](https://github.com/WenHsuanYu/SimpleMatch/issues/119) owns dependency-gated deletion,
[#120](https://github.com/WenHsuanYu/SimpleMatch/issues/120) owns the already-implemented QuickFIX
publication removal, and [#139](https://github.com/WenHsuanYu/SimpleMatch/issues/139) owns the Account
reservation v2 RPC replacement required before Account v1 deletion.

- [x] Commit 15.1: Remove runtime Market Reference service/database/outbox/Kafka paths after the
  offline builder and artifact loaders pass acceptance.
- [x] Commit 15.2: Cut all in-repository producers and consumers to `matching.commands` and
  `matching.events`, then delete the retired topic contracts/config.
- [x] Commit 15.3: Remove v1 adapters, obsolete string price/quantity representations, and custom
  configuration compatibility types after the coordinated cutover.
- [x] Commit 15.4: Remove unused dependencies, aliases, dead wiring, and non-critical QuickFIX
  execution projection/DLQ paths.
- [ ] Commit 15.5: Run the deletion test against new interfaces and remove pass-through modules.
- [ ] Commit 15.6: Update the event catalog, data dictionary, configuration matrix, deployment
  guide, and recovery runbooks.
- [ ] Commit 15.7: Update implementation-progress tracking only after all final gates pass.

Final gate:

- [ ] All acceptance criteria in this plan are satisfied.
- [ ] Java tests, static analysis, QuickFIX certification, Flyway checks, Debezium integration,
  Redis recovery, C++ deterministic tests, and Kubernetes validation pass.
- [ ] No unresolved warnings or undocumented compatibility shims remain.

Rollback:

- Transition cleanup occurs only after the new interfaces have been stable for one complete
  validation cycle.

## Decision Document

- The repository remains a polyglot monorepo.
- The C++ matching engine remains the owner of deterministic order-book matching and is a new
  capability in the current checkout.
- Existing Java services remain Spring Boot applications.
- Spring Cloud is limited to compatible dependency management and concrete Kubernetes integration.
- Kubernetes Service DNS replaces application-managed discovery.
- Spring Environment is the configuration authority.
- ConfigMaps contain non-sensitive values; Secrets contain sensitive values.
- Configuration changes activate through rolling restart, not live context refresh.
- Phase 1 Trading Release market scope is XTAI and ROCO regular-board common stocks during
  continuous trading.
- Exceptional instruments, call-auction sessions, financing, short sales, amendments, fees, tax,
  clearing, settlement, and customer onboarding are excluded.
- TWD is the canonical currency code.
- UTC instants and Asia/Taipei market dates have distinct explicit meanings.
- Internal identifiers and FIX business identity are distinct.
- v2 Protobuf contracts use fixed-point prices and notionals plus share quantities.
- State tables remain authoritative; event sourcing is not introduced.
- FIX-to-risk admission is synchronous.
- Account reservation uses a durable saga.
- Service state and outbox records commit atomically.
- Debezium publishes outbox records only.
- Kafka delivery is at least once and consumers are idempotent.
- Ordering is per domain stream, never global.
- Redis is an eventually consistent, rebuildable read model.
- Existing raw manifests evolve into reusable Kubernetes bases and overlays.
- PostgreSQL and Kafka are externally managed outside local development.
- Lombok is limited to narrow Spring boilerplate.
- Java code is organized by business capability.
- Expected business failures are typed outcomes; exceptions represent infrastructure faults or
  invariant violations.

## Testing Decisions

Good tests cross the same interface used by callers and assert observable behavior. They avoid
private-method verification and oversized mock graphs.

Required test layers:

- Pure table-driven tests for price, quantity, identity, calendar, session, tick, price-limit, and
  order-condition rules
- Module-interface tests for market reference, reservation, durable admission, FIX admission,
  matching, and projection behavior
- Spring configuration binding and profile tests
- PostgreSQL Testcontainers tests for V1 migrations, constraints, repositories, inbox, outbox, and
  transaction rollback
- Kafka and Debezium integration tests for payloads, keys, ordering, duplicate delivery, retries,
  quarantine, and recovery
- Redis integration tests for idempotent projection, fallback, outage, and rebuild
- Protobuf compatibility checks for field numbers and schema evolution
- QuickFIX certification for session lifecycle and supported order flows
- Deterministic C++ matching tests and replay fixtures
- Kubernetes configuration and smoke validation

Existing submission, repository, Flyway, application-context, and QuickFIX certification tests
provide prior art. Tests that assert legacy migration steps will be replaced by clean-schema and
invariant tests after the approved pre-release migration reset.

## Out of Scope

- Rewriting the matching engine in Java
- Full event sourcing
- Global Kafka ordering
- End-to-end exactly-once claims
- Cross-service database joins or foreign keys
- Runtime Flyway migration
- Live configuration refresh
- Eureka, Ribbon, a standalone Spring Cloud Config Server, or duplicate REST command interfaces
- TWSE or TPEx call-auction matching
- Odd-lot, after-hours, emerging-stock, ETF, ETN, warrant, derivative, block, auction, and
  tender-offer trading
- Disposition, altered-trading-method, suspended, or no-price-limit securities
- Margin purchase, short sale, securities lending, or day-trading exemptions
- In-place price or quantity amendment
- Brokerage fees, securities transaction tax, clearing, and T+2 settlement
- Customer onboarding, KYC, deposits, and withdrawals
- Live exchange or vendor feed integration before protocol and entitlement requirements are known
- Production SLO commitments before a workload model exists
- Dynamic distributed FIX-session leasing
- PostgreSQL table partitioning without measured need
- Redis as authoritative state

## Rollback Strategy

- Preserve the pre-refactor baseline as an explicit commit.
- Keep v1 adapters until all v2 consumers pass their gates.
- Switch Spring wiring in dedicated commits after new modules pass interface tests.
- Deploy new services dark before routing production-like traffic.
- Pause Kafka consumers at committed offsets instead of mutating event history.
- Retain durable outbox and inbox state during messaging rollback.
- Recreate disposable development schemas from the selected Flyway history; never delete only
  schema-history rows.
- Disable Redis and streaming paths independently without affecting trading.
- Do not perform transition cleanup until one full validation cycle passes.

## Bad-Smell Avoidance Checklist

- [ ] No duplicated market, identity, retry, or configuration policy across callers
- [ ] No long orchestration method that owns unrelated protocol, transaction, and messaging behavior
- [ ] No large configuration or validator class that exposes every concern
- [ ] No long primitive parameter lists; use validated domain carriers
- [ ] No data clumps for FIX identity, event metadata, or market context
- [ ] No generic manager, helper, util, or service naming where a domain term exists
- [ ] No unexplained literal market times, percentages, topic names, or scales
- [ ] No static global state for clocks, calendars, snapshots, or configuration
- [ ] No unresolved compiler, migration, connector, or schema warnings
- [ ] New modules pass the deletion test and provide leverage through small interfaces

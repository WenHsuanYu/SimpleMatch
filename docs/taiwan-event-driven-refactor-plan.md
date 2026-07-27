# Taiwan Event-Driven Refactor Plan

## Status

- Design interview: complete
- Shared implementation brief: confirmed
- Production implementation: not started
- Delivery model: incremental, test-first, and documentation-aligned

This plan describes both refactoring of existing modules and creation of target
capabilities that are documented but not yet present in the repository. New
capabilities are labeled explicitly so they are not mistaken for behavior that
already exists.

## Problem Statement

SimpleMatch has an intended polyglot, event-driven architecture, but the current
implementation is incomplete and several concerns are shallow or spread across
callers:

- Configuration is represented by a shared object and custom loading behavior
  instead of one validated Spring property-source mechanism.
- Database migrations preserve development history that can now be replaced by
  clean, business-typed V1 schemas because no shared or production data exists.
- Order fields cross boundaries as strings and are later stored in broad
  numeric or text columns.
- Risk admission, idempotency, persistence, outbox construction, and duplicate
  recovery expose more implementation detail than callers should need.
- Account reservation is a cross-service consistency concern without a complete
  durable recovery process.
- Kafka, Debezium, outbox, retry, ordering, duplicate handling, and recovery
  policies need one consistent contract.
- Taiwan market rules, market-reference authority, and session behavior are not
  implemented end to end.
- Redis is planned but not implemented as a read model.
- The documented C++ matching engine, market-data services, and query service
  are not present in the current source tree.

The refactor must preserve current behavior while replacing shallow interfaces
with deep modules, adding missing target capabilities in controlled later
phases, and avoiding a repository-wide rewrite.

## Acceptance Criteria

### Repository and compatibility

- [ ] The intended current worktree is reviewed and checkpointed before the
  refactor begins.
- [ ] Every commit leaves the repository buildable and its affected module
  tests passing.
- [ ] Existing FIX 4.4 behavior remains available throughout the transition.
- [ ] Existing v1 gRPC and Protobuf consumers remain supported by temporary
  adapters until all in-repository consumers use v2.
- [ ] Compatibility adapters are removed before the first public release.
- [ ] Target architecture documentation and implementation-progress tracking
  remain separate.

### Configuration

- [ ] Spring Environment is the only runtime configuration authority.
- [ ] Typed configuration binding validates required values during startup.
- [ ] Exactly one environment profile is active: local, test, staging, or
  production.
- [ ] Kubernetes is treated as a deployment platform, not another environment
  profile.
- [ ] Configuration precedence matches Spring Boot behavior and is covered by
  tests.
- [ ] ConfigMaps and Secrets have disjoint key ownership.
- [ ] Sensitive values never appear in committed YAML, ConfigMaps, fixtures, or
  logs.
- [ ] Staging and production configuration changes require a controlled rolling
  restart.
- [ ] Missing required configuration fails startup with a useful diagnostic.

### Taiwan market model

- [ ] Phase one supports XTAI and ROCO regular-board listed common stocks during
  continuous trading.
- [ ] Phase one supports all six combinations of limit or market price with ROD,
  IOC, or FOK.
- [ ] TWD is the only phase-one trading currency.
- [ ] Absolute timestamps are UTC instants; trading dates and session rules use
  Asia/Taipei.
- [ ] Market calendars, holidays, trading sessions, instruments, board-lot
  sizes, tick sizes, price limits, and eligibility come from a versioned daily
  market snapshot.
- [ ] Order-critical modules load the same active snapshot before becoming
  ready.
- [ ] Missing or stale market-reference data fails closed.
- [ ] Exceptional securities and unsupported sessions are rejected with stable
  reason codes.
- [ ] New orders are rejected outside continuous trading; cancellation remains
  available for open orders.
- [ ] Remaining ROD orders expire at the supported session boundary.
- [ ] IOC may partially fill and cancels its remainder.
- [ ] FOK either fills completely or cancels without any fill.
- [ ] Market ROD follows Taiwan market-order priority and converted-reference
  price rules.
- [ ] Intraday volatility interruption pauses new-order admission until auction
  behavior is implemented.

### Data model

- [ ] Each service has one clean, final V1 Flyway migration for an empty schema.
- [ ] Old development migration chains remain recoverable from Git history or a
  pre-reset tag, not from active migration directories.
- [ ] Flyway does not silently baseline unexpectedly non-empty schemas.
- [ ] Services own schemas and credentials; there are no cross-service foreign
  keys or direct joins.
- [ ] Internal identifiers use UUIDv7 semantics, Java UUID, and PostgreSQL UUID.
- [ ] FIX business identity remains sender, target, trading day, and client
  order ID.
- [ ] Prices use signed 64-bit fixed-point values in 1/10,000 TWD units.
- [ ] Quantities use signed 64-bit share counts.
- [ ] TWD notionals and reservations use signed 64-bit fixed-point values in
  1/10,000 TWD units.
- [ ] Required business values are explicit and non-null.
- [ ] Status-like values use bounded text plus check constraints, never numeric
  enum ordinals.
- [ ] JSONB is limited to genuinely variable diagnostic or projection metadata.
- [ ] Every non-constraint index is justified by a named query or operational
  scan.
- [ ] Trading and audit facts are immutable; physical cleanup is limited to
  disposable operational data.

### Event-driven processing

- [ ] PostgreSQL state remains authoritative for account, risk, idempotency, and
  durable projections.
- [ ] Commands and events are distinct Protobuf contracts.
- [ ] Every event has the agreed metadata envelope and stable schema version.
- [ ] State changes and outbox inserts commit in one local transaction.
- [ ] Debezium captures outbox tables only for business-event publication.
- [ ] Outbox payloads contain complete serialized Protobuf envelopes as binary
  data.
- [ ] Kafka delivery is treated as at least once.
- [ ] Database-writing consumers record inbox deduplication and business changes
  in one transaction.
- [ ] Ordering is guaranteed only within the relevant domain stream.
- [ ] Matching commands partition by instrument; account-originated events
  partition by account.
- [ ] Critical consumers preserve partition order during retries and quarantine
  rather than skip poison events.
- [ ] Non-critical projections may use delayed retry and dead-letter topics.
- [ ] Business rejection is a domain outcome, never a dead-letter event.
- [ ] Kafka is not the sole permanent audit archive.
- [ ] Event-delivery backlog is bounded by an admission backpressure policy.
- [ ] Delayed commands never execute in a later trading session.

### Admission and account consistency

- [ ] FIX gateway to risk admission remains synchronous.
- [ ] Risk durably records pending reservation work before calling account.
- [ ] Account is the sole authority for cash, positions, limits, and
  reservations.
- [ ] Reserve operations are deterministic and idempotent.
- [ ] Risk finalizes admission and its outbox only after reservation succeeds.
- [ ] Recovery resumes incomplete admission sagas after crashes and timeouts.
- [ ] Matching receives orders only after completed admission.
- [ ] Account consumes lifecycle events idempotently to settle or release
  reservations.
- [ ] Concurrent account mutations cannot over-reserve cash or positions.
- [ ] Idempotency records live at least as long as corresponding order and audit
  history.

### Redis and query paths

- [ ] Redis contains rebuildable, eventually consistent projections only.
- [ ] Redis failure cannot stop admission, reservation, or matching.
- [ ] Projection responses expose freshness metadata where required.
- [ ] Redis misses and outages fall back to PostgreSQL projections.
- [ ] Redis projections can be rebuilt through event replay.
- [ ] Query handling never scans Kafka.
- [ ] Market-data streaming uses a Redis snapshot followed by ordered Kafka
  deltas and resynchronizes on sequence gaps.

### Operations and quality

- [ ] Staging and production require authenticated encrypted PostgreSQL, Kafka,
  and gRPC connections.
- [ ] Insecure transport is allowed only by explicit local or test policy.
- [ ] Liveness reports process health; readiness reports ability to perform the
  service's required business role.
- [ ] Structured logs and OpenTelemetry context cross gRPC and Kafka boundaries.
- [ ] Logs never expose secrets, full account data, or raw FIX payloads by
  default.
- [ ] Metrics cover admission, reservation, outbox, CDC, consumer lag, retries,
  duplicates, sequence gaps, and quarantined partitions.
- [ ] Kubernetes resources use reusable bases and environment overlays.
- [ ] PostgreSQL and Kafka remain externally managed staging and production
  dependencies.
- [ ] Flyway migrations execute through deployment jobs, not application
  startup.
- [ ] Java static analysis, QuickFIX certification, C++ tests, schema tests,
  contract checks, and deployment validation pass.

## Solution

The solution is an incremental vertical-slice migration.

Existing Java services remain Spring Boot applications. Spring Cloud is used
only where it adds concrete platform value: Kubernetes configuration integration
and compatible dependency management. Kubernetes Service DNS remains the
discovery mechanism. Existing gRPC and FIX seams remain in place.

The first behavioral slice makes the limit-ROD order path correct end to end.
It introduces typed v2 contracts, market-reference snapshots, account
reservation, durable risk admission, binary transactional outbox publication,
and matching integration. Later slices add IOC, FOK, market ROD, read models,
and streaming through the same interfaces.

Deep modules concentrate policy:

- A configuration-resolution module binds and validates Spring properties.
- A market-reference module owns snapshot import, validation, and activation.
- An account-reservation module owns funds and position authority.
- A durable-admission module owns idempotency, saga state, final outcome, and
  outbox atomicity.
- A FIX-admission module owns protocol normalization, recovery, and response
  projection.
- A matching module owns deterministic book state and Taiwan execution rules.
- Projection modules own idempotent PostgreSQL and Redis read models.

New deployable capabilities are added only after their upstream interfaces are
stable. They include the C++ matching engine, market-data publisher,
market-data streamer, and query service.

## Commit Plan

Each item below is intended to be one small commit unless its acceptance test
shows it must be split further. Every commit runs the narrowest relevant tests
before the broader phase gate.

### Phase 0: Establish a trustworthy baseline

- [x] Commit 0.1: Review the current dirty worktree, classify intended source
  changes versus generated or runtime artifacts, and checkpoint only intended
  work.
- [x] Commit 0.2: Record the current module inventory and label documented but
  missing deployables as target capabilities.
- [x] Commit 0.3: Add black-box characterization tests for the current FIX new
  order, cancellation, risk response, durable submission, and outbox behavior.
- [x] Commit 0.4: Add a machine-checked compatibility inventory for current v1
  Protobuf messages and field numbers.
- [x] Commit 0.5: Record the passing baseline validation results and known
  environment-only blockers.

Phase gate:

- [x] Current behavior is characterized.
- [x] No target capability is described as already implemented.
- [x] The baseline commit is recoverable.

Rollback:

- Revert only the characterization commits; no production behavior has changed.

### Phase 1: Consolidate build and dependency policy

- [ ] Commit 1.1: Move shared library and plugin versions into the version
  catalog without changing resolved versions.
- [ ] Commit 1.2: Add a Spring service convention module and migrate one
  no-behavior-change service as proof.
- [ ] Commit 1.3: Migrate the remaining Spring services to the convention
  module one at a time.
- [ ] Commit 1.4: Add a Protobuf convention module and migrate contract
  generation without changing generated interfaces.
- [ ] Commit 1.5: Deepen the existing Flyway convention around service identity,
  schema, migration location, and validation tasks.
- [ ] Commit 1.6: Remove root build path predicates and duplicated dependency
  declarations made obsolete by conventions.
- [ ] Commit 1.7: Add dependency locking or verification appropriate to the
  repository's release workflow.

Phase gate:

- [ ] Dependency resolution is unchanged except for explicitly documented
  corrections.
- [ ] All Java tests and static analysis pass.
- [ ] Flyway task discovery remains intact.

Rollback:

- Revert convention migrations service by service; module behavior is unchanged.

### Phase 2: Make Spring configuration authoritative

- [ ] Commit 2.1: Add tests for base YAML, profile YAML, environment overrides,
  and test-only override precedence.
- [ ] Commit 2.2: Add service-scoped typed configuration objects with startup
  validation while retaining the existing compatibility facade.
- [ ] Commit 2.3: Bind the compatibility facade from Spring Environment instead
  of independent file discovery.
- [ ] Commit 2.4: Add environment-profile exclusivity and staging or production
  security-policy validation.
- [ ] Commit 2.5: Add Kubernetes ConfigMap and Secret imports with disjoint key
  validation.
- [ ] Commit 2.6: Add fail-fast behavior for missing required Kubernetes
  configuration.
- [ ] Commit 2.7: Remove custom environment alias resolution after every caller
  uses typed properties.
- [ ] Commit 2.8: Remove the custom loader after compatibility tests prove it is
  unused.
- [ ] Commit 2.9: Document the configuration matrix, precedence, secret
  ownership, and restart policy.

Phase gate:

- [ ] The same property names bind in local, test, staging, and production.
- [ ] ConfigMap and Secret conflicts fail validation.
- [ ] Sensitive values are absent from committed configuration.
- [ ] Each Spring application starts under local and test profiles.

Rollback:

- Keep the compatibility facade and revert one binding group at a time.

### Phase 3: Introduce v2 domain contracts

- [ ] Commit 3.1: Add the common v2 event metadata envelope and schema
  compatibility checks.
- [ ] Commit 3.2: Add typed UUID-backed identifiers and validation rules.
- [ ] Commit 3.3: Add fixed-point price, TWD notional, and share-quantity
  contracts.
- [ ] Commit 3.4: Add instrument identity, venue MIC, trading day, snapshot ID,
  and session-state contracts.
- [ ] Commit 3.5: Add v2 new-order and cancel commands.
- [ ] Commit 3.6: Add v2 admission outcome events.
- [ ] Commit 3.7: Add v2 reservation commands and account lifecycle events.
- [ ] Commit 3.8: Add v2 matching lifecycle and execution events.
- [ ] Commit 3.9: Add v1-to-v2 ingress adapters with round-trip compatibility
  tests.
- [ ] Commit 3.10: Add stable rejection and cancellation reason catalogs.

Phase gate:

- [ ] Field numbers are never reused.
- [ ] Invalid UUID, price, quantity, currency, and timestamp values fail at the
  intended seam.
- [ ] Existing v1 behavior remains available through adapters.

Rollback:

- v2 is additive; revert consumers independently while retaining v1.

### Phase 4: Reset Flyway histories into typed V1 schemas

- [ ] Commit 4.1: Add a reviewed data dictionary containing business meaning,
  units, ranges, nullability, constraints, and query ownership.
- [ ] Commit 4.2: Tag or otherwise checkpoint the old migration histories before
  active scripts are replaced.
- [ ] Commit 4.3: Replace the account migration chain with one typed V1 schema
  and clean-install migration test.
- [ ] Commit 4.4: Replace the risk migration chain with one typed V1 schema and
  clean-install migration test.
- [ ] Commit 4.5: Replace the persistence migration chain with one typed V1
  schema and clean-install migration test.
- [ ] Commit 4.6: Add consistent inbox tables and uniqueness constraints to
  database-writing consumers.
- [ ] Commit 4.7: Add the binary outbox table shape to event-originating
  services.
- [ ] Commit 4.8: Replace legacy-upgrade tests with empty-schema and invariant
  tests.
- [ ] Commit 4.9: Disable permissive baseline-on-migrate behavior for ordinary
  clean installations.
- [ ] Commit 4.10: Update Flyway smoke checks and schema documentation for the
  reset.

Phase gate:

- [ ] Every service migrates from an empty database.
- [ ] Re-running migrate is a no-op.
- [ ] Constraints reject invalid business values.
- [ ] Repository queries have justified indexes and reviewed plans.

Rollback:

- Restore old migration directories from the checkpoint and recreate disposable
  development schemas.

### Phase 5: Create the market-reference publisher capability

- [ ] Commit 5.1: Scaffold the documented market-data publisher as a Spring Boot
  service without runtime consumers.
- [ ] Commit 5.2: Add immutable market snapshot types and fixture-based tests.
- [ ] Commit 5.3: Add Taiwan trading-calendar and holiday resolution tests.
- [ ] Commit 5.4: Add instrument identity, venue, board-lot, tick-table, and
  eligibility import validation.
- [ ] Commit 5.5: Add daily reference-price and absolute price-limit validation.
- [ ] Commit 5.6: Add snapshot persistence with source timestamp, checksum, and
  activation state.
- [ ] Commit 5.7: Add snapshot publication through the service outbox.
- [ ] Commit 5.8: Add readiness behavior for missing, stale, or invalid daily
  snapshots.
- [ ] Commit 5.9: Add deterministic replay and simulator adapters for local and
  test environments.

Phase gate:

- [ ] XTAI and ROCO fixtures produce deterministic snapshots.
- [ ] Unsupported instruments carry explicit eligibility reasons.
- [ ] No trading module calls an exchange website synchronously.

Rollback:

- The new service is additive and may remain undeployed.

### Phase 6: Deepen account reservation authority

- [ ] Commit 6.1: Add tests for available cash, available positions, limits, and
  reservation invariants.
- [ ] Commit 6.2: Introduce typed account, balance, position, limit, and
  reservation domain values.
- [ ] Commit 6.3: Implement idempotent reserve behavior in one local transaction.
- [ ] Commit 6.4: Add database-enforced account concurrency control.
- [ ] Commit 6.5: Add reservation-created and reservation-rejected outbox events.
- [ ] Commit 6.6: Add inbox-based execution-event deduplication.
- [ ] Commit 6.7: Apply full and partial fills to authoritative account state.
- [ ] Commit 6.8: Release remaining reservations for cancel, expiry, IOC
  remainder, and FOK cancellation.
- [ ] Commit 6.9: Add administrative account and position provisioning for
  development and controlled environments.

Phase gate:

- [ ] Concurrent reserves cannot overspend cash or positions.
- [ ] Duplicate reserve and lifecycle messages are harmless.
- [ ] Account state and its outbox commit atomically.

Rollback:

- Keep the existing account interface behind an adapter until the new module
  passes concurrency and integration tests.

### Phase 7: Deepen durable risk admission

- [ ] Commit 7.1: Add table-driven tests for transport-independent submission
  validation.
- [ ] Commit 7.2: Extract the FIX business-identity and content-equivalence
  policy into one module.
- [ ] Commit 7.3: Add tests for equivalent replay, conflicting replay, and
  concurrent duplicate submission.
- [ ] Commit 7.4: Introduce a durable admission journal interface that owns saga
  state and local transaction boundaries.
- [ ] Commit 7.5: Persist pending reservation state before external account
  calls.
- [ ] Commit 7.6: Add the idempotent account reservation adapter.
- [ ] Commit 7.7: Finalize accepted admission and binary outbox event atomically.
- [ ] Commit 7.8: Finalize business rejection and binary outbox event atomically.
- [ ] Commit 7.9: Add recovery of pending admissions after timeout or restart.
- [ ] Commit 7.10: Add backpressure behavior based on CDC delivery lag.
- [ ] Commit 7.11: Expose the deep admission interface through v2 gRPC.
- [ ] Commit 7.12: Route v1 gRPC through the compatibility adapter.

Phase gate:

- [ ] No database transaction remains open across a network call.
- [ ] Every pending saga reaches a recoverable terminal state.
- [ ] Equivalent retries return the same outcome and event identity.
- [ ] Conflicting retries return a stable idempotency conflict.

Rollback:

- Retain the current admission adapter until the new journal has passed
  integration tests; switch wiring in a dedicated commit.

### Phase 8: Deepen the QuickFIX admission and session modules

- [ ] Commit 8.1: Add FIX mapping tests for all v2 identifiers and fixed-point
  values.
- [ ] Commit 8.2: Add mapping tests for all six price and time-in-force
  combinations.
- [ ] Commit 8.3: Introduce a deep FIX-admission module around normalization,
  risk submission, WAL recovery, and FIX outcome projection.
- [ ] Commit 8.4: Make gateway WAL replay resubmit unresolved work through the
  idempotent risk interface.
- [ ] Commit 8.5: Prevent WAL replay from publishing matching commands directly.
- [ ] Commit 8.6: Deepen the session directory around route, cancel context,
  lifecycle status, and execution deduplication.
- [ ] Commit 8.7: Preserve explicit single-owner session assignment and reject
  conflicting ownership.
- [ ] Commit 8.8: Add cancellation behavior during admission pauses and market
  interruptions.
- [ ] Commit 8.9: Update outbound execution and cancellation projection to v2
  lifecycle events.
- [ ] Commit 8.10: Extend QuickFIX certification for v1 compatibility and v2
  internal behavior.

Phase gate:

- [ ] Raw FIX remains adapter-level audit data.
- [ ] Gateway replay cannot create duplicate admitted orders.
- [ ] One active owner exists per FIX session.
- [ ] Certification covers new, cancel, duplicate, and recovery paths.

Rollback:

- Keep v1 mapping and old wiring available until certification passes.

### Phase 9: Establish binary outbox CDC and Kafka policy

- [ ] Commit 9.1: Configure Debezium Outbox Event Router for binary payload
  pass-through in local infrastructure.
- [ ] Commit 9.2: Restrict each connector to its service outbox table.
- [ ] Commit 9.3: Add domain-stream topic naming and partition-key tests.
- [ ] Commit 9.4: Add outbox-to-Kafka integration tests for exact payload bytes,
  keys, headers, timestamps, and duplicates.
- [ ] Commit 9.5: Add schema compatibility validation to continuous integration.
- [ ] Commit 9.6: Add ordered in-place retry and partition quarantine for
  critical consumers.
- [ ] Commit 9.7: Add delayed retry and dead-letter handling for non-critical
  projections.
- [ ] Commit 9.8: Add Debezium lag, outbox age, consumer lag, duplicate, and
  quarantine metrics.
- [ ] Commit 9.9: Add bounded outbox cleanup after the configured CDC safety
  window.

Phase gate:

- [ ] Database commits survive Kafka and connector outages.
- [ ] Replayed or duplicated events do not duplicate state changes.
- [ ] Critical records never overtake a failed earlier record in the partition.

Rollback:

- Stop connectors and keep durable outbox rows; no business state rollback is
  required.

### Phase 10: Create the C++ matching engine capability

- [ ] Commit 10.1: Scaffold the documented C++ matching engine with deterministic
  unit-test and build targets.
- [ ] Commit 10.2: Add UUID, fixed-point price, share quantity, and instrument
  value types.
- [ ] Commit 10.3: Add v2 command decoding and schema compatibility fixtures.
- [ ] Commit 10.4: Add immutable market snapshot loading and version checks.
- [ ] Commit 10.5: Implement price-time priority for limit-ROD orders through
  tests.
- [ ] Commit 10.6: Emit deterministic partial and full execution events.
- [ ] Commit 10.7: Add session-close commands and ROD expiration events.
- [ ] Commit 10.8: Add IOC matching and remainder cancellation.
- [ ] Commit 10.9: Add atomic FOK depth evaluation and all-or-none execution.
- [ ] Commit 10.10: Add market-order priority and converted-reference price
  behavior.
- [ ] Commit 10.11: Add market-ROD resting and terminal cancellation behavior.
- [ ] Commit 10.12: Add volatility-interruption pause and cancellation behavior.
- [ ] Commit 10.13: Add delayed-command expiration by trading day and session.
- [ ] Commit 10.14: Add Kafka command consumption and execution publication.

Phase gate:

- [ ] Replaying the same ordered command stream produces identical outcomes.
- [ ] FOK never partially fills.
- [ ] IOC never rests.
- [ ] Market ROD follows the confirmed Taiwan behavior.
- [ ] Unknown snapshot versions pause processing.

Rollback:

- Keep the engine undeployed until deterministic tests and Kafka integration
  pass; admitted commands remain durable in Kafka.

### Phase 11: Complete account lifecycle integration

- [ ] Commit 11.1: Consume execution and terminal order events in account
  service.
- [ ] Commit 11.2: Enforce event ID deduplication in the same transaction as
  account mutation.
- [ ] Commit 11.3: Enforce aggregate-sequence gap detection and quarantine.
- [ ] Commit 11.4: Add crash-recovery tests between database commit and Kafka
  acknowledgment.
- [ ] Commit 11.5: Publish account lifecycle outcomes through the account
  outbox.

Phase gate:

- [ ] Every terminal matching outcome settles or releases its reservation.
- [ ] Duplicate and replayed lifecycle events preserve exact account state.

Rollback:

- Pause the consumer at its committed offset; authoritative reservations remain
  queryable for reconciliation.

### Phase 12: Build durable projections and Redis read models

- [ ] Commit 12.1: Add idempotent PostgreSQL order and execution projections.
- [ ] Commit 12.2: Add projection rebuild tests from retained event fixtures.
- [ ] Commit 12.3: Add a versioned Redis key schema for order and execution read
  models.
- [ ] Commit 12.4: Add idempotent Redis projection updates after durable
  PostgreSQL projection commits.
- [ ] Commit 12.5: Add Redis outage and PostgreSQL fallback behavior.
- [ ] Commit 12.6: Add Redis rebuild tooling and freshness metadata.
- [ ] Commit 12.7: Scaffold the documented read-only query service.
- [ ] Commit 12.8: Add Redis-first order and execution queries with PostgreSQL
  fallback.
- [ ] Commit 12.9: Add account-summary and active-market-snapshot queries.

Phase gate:

- [ ] Redis can be deleted and rebuilt without business-state loss.
- [ ] Query responses disclose freshness where required.
- [ ] Query handling does not read Kafka synchronously.

Rollback:

- Disable Redis reads and use PostgreSQL projections until Redis is rebuilt.

### Phase 13: Create market-data projection and streaming capabilities

- [ ] Commit 13.1: Convert matching execution and book-change facts into
  versioned market-data events.
- [ ] Commit 13.2: Build deterministic last-trade and top-five book projections.
- [ ] Commit 13.3: Persist market-data snapshots in Redis with sequence metadata.
- [ ] Commit 13.4: Scaffold the documented market-data streamer.
- [ ] Commit 13.5: Serve initial Redis snapshots before Kafka deltas.
- [ ] Commit 13.6: Detect sequence gaps and resynchronize instead of streaming
  inconsistent deltas.
- [ ] Commit 13.7: Add slow-consumer backpressure and disconnect policy.

Phase gate:

- [ ] Initial snapshot and subsequent deltas form one monotonic sequence.
- [ ] Redis loss causes resynchronization, not trading-path failure.
- [ ] Public depth is limited to last trade and best five levels.

Rollback:

- Disable streaming while retaining matching and durable projections.

### Phase 14: Add Kubernetes deployment and security policy

- [ ] Commit 14.1: Convert one workload to a reusable Kubernetes base and
  environment overlays.
- [ ] Commit 14.2: Convert remaining existing workloads one at a time.
- [ ] Commit 14.3: Add overlays for newly created capabilities as they become
  deployable.
- [ ] Commit 14.4: Add ConfigMap and Secret references without committed secret
  values.
- [ ] Commit 14.5: Add checksum-driven rolling restart behavior.
- [ ] Commit 14.6: Add service-scoped Flyway migration jobs.
- [ ] Commit 14.7: Add Debezium connector deployment configuration and
  least-privilege access.
- [ ] Commit 14.8: Add authenticated encrypted staging and production
  connectivity policy.
- [ ] Commit 14.9: Add liveness, readiness, startup, and market-snapshot health
  semantics.
- [ ] Commit 14.10: Add structured logging, OpenTelemetry propagation, metrics,
  and alert rules.
- [ ] Commit 14.11: Validate manifests and run deployment smoke tests.

Phase gate:

- [ ] Local, test, staging, and production overlays use the same property names.
- [ ] Missing Secrets fail startup.
- [ ] Applications do not run schema migrations at startup.
- [ ] Redis, Kafka, PostgreSQL, and market-snapshot failures produce the agreed
  readiness behavior.

Rollback:

- Roll back one workload overlay or image at a time; schema jobs remain
  independently auditable.

### Phase 15: Remove transition scaffolding

- [ ] Commit 15.1: Remove v1 adapters after every in-repository consumer uses v2.
- [ ] Commit 15.2: Remove obsolete string price and quantity representations.
- [ ] Commit 15.3: Remove obsolete custom configuration compatibility types.
- [ ] Commit 15.4: Remove unused dependencies, aliases, and dead wiring.
- [ ] Commit 15.5: Run the deletion test against new interfaces and remove
  pass-through modules.
- [ ] Commit 15.6: Update the event catalog, data dictionary, configuration
  matrix, deployment guide, and recovery runbooks.
- [ ] Commit 15.7: Update implementation-progress tracking only after all final
  gates pass.

Final gate:

- [ ] All acceptance criteria in this plan are satisfied.
- [ ] Java tests, static analysis, QuickFIX certification, Flyway checks,
  Debezium integration, Redis recovery, C++ deterministic tests, and Kubernetes
  validation pass.
- [ ] No unresolved warnings or undocumented compatibility shims remain.

Rollback:

- Transition cleanup occurs only after the new interfaces have been stable for
  one complete validation cycle.

## Decision Document

- The repository remains a polyglot monorepo.
- The C++ matching engine remains the owner of deterministic order-book
  matching and is a new capability in the current checkout.
- Existing Java services remain Spring Boot applications.
- Spring Cloud is limited to compatible dependency management and concrete
  Kubernetes integration.
- Kubernetes Service DNS replaces application-managed discovery.
- Spring Environment is the configuration authority.
- ConfigMaps contain non-sensitive values; Secrets contain sensitive values.
- Configuration changes activate through rolling restart, not live context
  refresh.
- Phase-one market scope is XTAI and ROCO regular-board common stocks during
  continuous trading.
- Exceptional instruments, call-auction sessions, financing, short sales,
  amendments, fees, tax, clearing, settlement, and customer onboarding are
  excluded.
- TWD is the canonical currency code.
- UTC instants and Asia/Taipei market dates have distinct explicit meanings.
- Internal identifiers and FIX business identity are distinct.
- v2 Protobuf contracts use fixed-point prices and notionals plus share
  quantities.
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
- Expected business failures are typed outcomes; exceptions represent
  infrastructure faults or invariant violations.

## Testing Decisions

Good tests cross the same interface used by callers and assert observable
behavior. They avoid private-method verification and oversized mock graphs.

Required test layers:

- Pure table-driven tests for price, quantity, identity, calendar, session,
  tick, price-limit, and order-condition rules
- Module-interface tests for market reference, reservation, durable admission,
  FIX admission, matching, and projection behavior
- Spring configuration binding and profile tests
- PostgreSQL Testcontainers tests for V1 migrations, constraints, repositories,
  inbox, outbox, and transaction rollback
- Kafka and Debezium integration tests for payloads, keys, ordering, duplicate
  delivery, retries, quarantine, and recovery
- Redis integration tests for idempotent projection, fallback, outage, and
  rebuild
- Protobuf compatibility checks for field numbers and schema evolution
- QuickFIX certification for session lifecycle and supported order flows
- Deterministic C++ matching tests and replay fixtures
- Kubernetes configuration and smoke validation

Existing submission, repository, Flyway, application-context, and QuickFIX
certification tests provide prior art. Tests that assert legacy migration steps
will be replaced by clean-schema and invariant tests after the approved
pre-release migration reset.

## Out of Scope

- Rewriting the matching engine in Java
- Full event sourcing
- Global Kafka ordering
- End-to-end exactly-once claims
- Cross-service database joins or foreign keys
- Runtime Flyway migration
- Live configuration refresh
- Eureka, Ribbon, a standalone Spring Cloud Config Server, or duplicate REST
  command interfaces
- TWSE or TPEx call-auction matching
- Odd-lot, after-hours, emerging-stock, ETF, ETN, warrant, derivative, block,
  auction, and tender-offer trading
- Disposition, altered-trading-method, suspended, or no-price-limit securities
- Margin purchase, short sale, securities lending, or day-trading exemptions
- In-place price or quantity amendment
- Brokerage fees, securities transaction tax, clearing, and T+2 settlement
- Customer onboarding, KYC, deposits, and withdrawals
- Live exchange or vendor feed integration before protocol and entitlement
  requirements are known
- Production SLO commitments before a workload model exists
- Dynamic distributed FIX-session leasing
- PostgreSQL table partitioning without measured need
- Redis as authoritative state

## Rollback Strategy

- Preserve the pre-refactor baseline as an explicit commit.
- Keep v1 adapters until all v2 consumers pass their gates.
- Switch Spring wiring in dedicated commits after new modules pass interface
  tests.
- Deploy new services dark before routing production-like traffic.
- Pause Kafka consumers at committed offsets instead of mutating event history.
- Retain durable outbox and inbox state during messaging rollback.
- Recreate disposable development schemas from the selected Flyway history;
  never delete only schema-history rows.
- Disable Redis and streaming paths independently without affecting trading.
- Do not perform transition cleanup until one full validation cycle passes.

## Bad-Smell Avoidance Checklist

- [ ] No duplicated market, identity, retry, or configuration policy across
  callers
- [ ] No long orchestration method that owns unrelated protocol, transaction,
  and messaging behavior
- [ ] No large configuration or validator class that exposes every concern
- [ ] No long primitive parameter lists; use validated domain carriers
- [ ] No data clumps for FIX identity, event metadata, or market context
- [ ] No generic manager, helper, util, or service naming where a domain term
  exists
- [ ] No unexplained literal market times, percentages, topic names, or scales
- [ ] No static global state for clocks, calendars, snapshots, or configuration
- [ ] No unresolved compiler, migration, connector, or schema warnings
- [ ] New modules pass the deletion test and provide leverage through small
  interfaces

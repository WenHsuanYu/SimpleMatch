# Phase 5 Market-Reference Publisher Evidence

`marketdata-publisher` is the owner of versioned daily market-reference snapshots. It accepts
offline source content only; trading services do not call an exchange website synchronously.

## Publication boundary

`MarketSnapshotImportService` parses, validates, normalizes, sorts, serializes, and checksums source
content before the database transaction. The public
`MarketSnapshotApplicationService.publishSnapshot` transaction owns duplicate lookup,
active-snapshot locking, version allocation, deactivation, immutable snapshot persistence,
activation, and the binary outbox insert.

The publication transaction is limited to 10 seconds. Its active-snapshot lock query has a tighter
2-second JDBC timeout, so lock contention fails rather than holding a broad market-data transaction
open.

`SnapshotOutboxRecord` is an internal marketdata-publisher carrier, not a cross-service Java API.
`JdbcSnapshotOutbox` is its only flattening adapter; the externally compatible boundary is the
transactional outbox row and emitted event, so the carrier may use semantic groups.

Each snapshot stores its source identity and timestamp, checksum, complete normalized content,
immutable version, and active state. The schema permits one active snapshot per trading day and one
result for a source-identity/checksum pair. A duplicate returns the stored publication; changed
source content creates the next version and atomically replaces the active version.

## Routing Policy publication

`RoutingPolicyApplicationService` publishes a separate immutable policy that references a committed
Market Snapshot by UUID. `RoutingPolicy` owns normalized instrument uniqueness, explicit partition
bounds, deterministic assignment ordering, and a half-open effective interval. Its policy row,
assignment rows, and binary protobuf outbox event commit in one local transaction; duplicate content
is idempotent and an overlapping interval is rejected.

The policy event is published on `market-reference.routing-policies` with the trading day as its
message key. Consumers receive the generated `simplematch.routing.v2.RoutingPolicy` payload and
must stage the complete assignment set before activation. Market Reference readiness remains out of
service when no policy applies to the current Asia/Taipei date, the interval is stale or not yet
effective, the policy is incomplete or invalid, or its declared `orders.validated` partition count
does not match the configured Kafka topology.

## Scope and fail-closed behavior

Fixtures cover XTAI and ROCO regular-board common stocks, Taiwan holiday and weekend rejection,
price-limit and tick-table validation, and an ETF retained with the stable
`UNSUPPORTED_SECURITY_TYPE` eligibility reason. Readiness is out of service without a snapshot for
the current Asia/Taipei trading date or when only an older active snapshot exists. It is also out of
service when the stored normalized content no longer matches its durable checksum.

`FixtureReplayMarketSnapshotInput` and `SimulatorMarketSnapshotInput` are offline deterministic
inputs for local and test usage. They make no HTTP call.

## Verification

Run the current service tests with:

```bash
./gradlew -q :services:marketdata-publisher:test
```

The completed Phase 5 source and fixture structure gate is retained as
historical regression evidence:

```bash
bash scripts/archive/check-phase-5-gate.sh
```

`MarketSnapshotPublicationPostgresIT` is an opt-in PostgreSQL verification. It requires an isolated
database where the
`marketdata_publisher` schema does not already exist, and removes that schema after the test:

```bash
./gradlew -q -Dphase5.postgres.dsn='jdbc:postgresql://host:5432/database?user=name&password=secret' \
  :services:marketdata-publisher:test --tests '*MarketSnapshotPublicationPostgresIT'
```

The normal transaction integration test covers proxied commit and rollback, database constraint
rejection, and a synchronized competing-first-publication race. The opt-in PostgreSQL test verifies
the Flyway migration and durable snapshot/outbox shape against a supplied PostgreSQL instance.

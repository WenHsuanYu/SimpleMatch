# Phase 5 Market-Reference Publisher Evidence

`marketdata-publisher` is the owner of versioned daily market-reference
snapshots. It accepts offline source content only; trading services do not call
an exchange website synchronously.

## Publication boundary

`MarketSnapshotImportService` parses, validates, normalizes, sorts, serializes,
and checksums source content before the database transaction. The public
`MarketSnapshotApplicationService.publishSnapshot` transaction owns duplicate
lookup, active-snapshot locking, version allocation, deactivation, immutable
snapshot persistence, activation, and the binary outbox insert.

The publication transaction is limited to 10 seconds. Its active-snapshot lock
query has a tighter 2-second JDBC timeout, so lock contention fails rather than
holding a broad market-data transaction open.

Each snapshot stores its source identity and timestamp, checksum, complete
normalized content, immutable version, and active state. The schema permits one
active snapshot per trading day and one result for a source-identity/checksum
pair. A duplicate returns the stored publication; changed source content creates
the next version and atomically replaces the active version.

## Scope and fail-closed behavior

Fixtures cover XTAI and ROCO regular-board common stocks, Taiwan holiday and
weekend rejection, price-limit and tick-table validation, and an ETF retained
with the stable `UNSUPPORTED_SECURITY_TYPE` eligibility reason. Readiness is
out of service without a snapshot for the current Asia/Taipei trading date or
when only an older active snapshot exists. It is also out of service when the
stored normalized content no longer matches its durable checksum.

`FixtureReplayMarketSnapshotInput` and `SimulatorMarketSnapshotInput` are
offline deterministic inputs for local and test usage. They make no HTTP call.

## Verification

Run the normal phase checks with:

```bash
./gradlew -q :services:marketdata-publisher:test
bash scripts/check-phase-5-gate.sh
```

`MarketSnapshotPublicationPostgresIT` is an opt-in PostgreSQL verification. It
requires an isolated database where the `marketdata_publisher` schema does not
already exist, and removes that schema after the test:

```bash
./gradlew -q -Dphase5.postgres.dsn='jdbc:postgresql://host:5432/database?user=name&password=secret' \
  :services:marketdata-publisher:test --tests '*MarketSnapshotPublicationPostgresIT'
```

The normal transaction integration test covers proxied commit and rollback,
database constraint rejection, and a synchronized competing-first-publication
race. The opt-in PostgreSQL test verifies the Flyway migration and durable
snapshot/outbox shape against a supplied PostgreSQL instance.

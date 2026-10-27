# Build one offline Market Reference Artifact per trading day

Status: accepted. Supersedes ADR 0006's runtime publication and projection model.

## Context

Before this decision, Market Reference used a Spring runtime service. The service persisted and
published snapshots and routing policies through PostgreSQL, an outbox, Debezium, and Kafka. Risk
and native Matching then installed local routing projections. That design required a runtime
service, ordered topics, projection recovery, and readiness states. It distributed data that
changes only at a controlled daily boundary.

SimpleMatch is pre-release and isolated. It requires one complete Phase 1 universe and stable
routing assignments for each Asia/Taipei trading day. It does not require intraday route changes
or a migration path that preserves compatibility for external consumers.

## Decision

Market Reference is an offline repository tool. It acquires and normalizes official TWSE and TPEx
facts. It produces one final immutable `market_reference.json` for each trading day. Risk and all
15 Matching pods mount and validate the exact same bytes at startup. The design has no runtime
Market Reference service, synchronous lookup, Market Reference outbox, or Market Reference Kafka
topic.

The artifact contains four sections:

- `metadata`.
- Reusable `marketRules`.
- Instrument facts in `marketSnapshot`.
- Complete, stable assignments in `routingPolicy`.

Every Phase 1 eligible XTAI and ROCO regular-board common stock has exactly one route. A known
unsupported instrument has an eligibility reason and no route. The topology has 15 fixed
partitions. Each partition can hold 150 instrument order books.

D-1 creates a preliminary candidate for review of the universe, eligibility, and stable assignments.
The trading-day build retrieves every official source again. It adds the day's official reference
and limit prices. Missing, stale, partial, or inconsistent source data fails closed. Human approval
uses the generated summary, diffs, anomalies, source checksums, and validation result. It does not
require review of every row.

Artifact identity is `tradingDay + contentSha256`. SHA-256 covers the exact UTF-8 file bytes.
The checksum remains outside the JSON to avoid a circular self-checksum. Git retains approved
artifacts and approval reports. A final artifact of 900 KiB or less uses an immutable ConfigMap.
A larger artifact uses a digest-pinned OCI data image and an init container.

Both delivery forms mount the same file path. Risk and Matching load the artifact only at startup.
The design permits no hot reload or intraday route change. An artifact or identity mismatch blocks
readiness. Gateway operational admission combines consumer readiness and opens only after every
required component agrees.

## Consequences

After startup loaders exist, the former runtime implementation becomes a removal target. This
includes runtime code, schemas, outbox connectors, topics, Risk projection, and native routing-policy
ingress. The offline builder may reuse pure source normalization, validation, calendar, tick,
eligibility, and canonical-codec logic.

The daily workflow and deployment become operational gates. The trading hot path no longer needs
a runtime service or an asynchronous projection lifecycle for Market Reference. The single
artifact also makes configuration equality between Risk and Matching directly observable.

## Considered options

- This decision rejects runtime outbox/Kafka publication. Controlled daily deployment does not
  justify the additional runtime consistency boundary.
- This decision rejects Yahoo Finance as the authority because official TWSE and TPEx sources are
  available.
- This decision rejects separate artifact files for each partition. Risk and every Matching pod
  must validate one authoritative envelope and identity.
- This decision rejects gzip fallback to preserve one direct file contract. Artifacts above the
  ConfigMap safety threshold use OCI delivery.

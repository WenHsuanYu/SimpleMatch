# Build one offline Market Reference Artifact per trading day

Status: accepted; supersedes ADR 0006's runtime publication and projection model.

## Context

The current repository models Market Reference as a Spring runtime service that persists and
publishes snapshots and routing policies through PostgreSQL, an outbox, Debezium, and Kafka. Risk
and native Matching then install local routing projections. That design adds a runtime service,
topic ordering, projection recovery, and readiness states to distribute data that changes only at a
controlled daily boundary.

SimpleMatch is pre-release and isolated. It requires one complete Phase 1 universe and stable route
assignment for each Asia/Taipei trading day, not intraday routing mutation or an externally
compatible migration path.

## Decision

Market Reference is an offline repository tool. It acquires and normalizes official TWSE and TPEx
facts and produces one final immutable `market_reference.json` for each trading day. Risk and all 15
Matching pods mount and validate the exact same bytes at startup. There is no runtime Market
Reference service, synchronous lookup, Market Reference outbox, or Market Reference Kafka topic.

The artifact contains four internal sections: `metadata`, reusable `marketRules`, instrument facts
in `marketSnapshot`, and complete stable assignments in `routingPolicy`. Every Phase 1 eligible XTAI
and ROCO regular-board common stock has exactly one route; a known unsupported instrument has an
eligibility reason and no route. The topology is fixed at 15 partitions with capacity for 150
instrument order books per partition.

D-1 creates a preliminary candidate for universe, eligibility, and stable assignment review. The
trading-day build re-fetches every official source and adds the day's official reference and limit
prices. Missing, stale, partial, or inconsistent source data fails closed. Human approval reviews a
generated summary, diffs, anomalies, source checksums, and validation result rather than every row.

Artifact identity is `tradingDay + contentSha256`, where SHA-256 covers the exact UTF-8 file bytes.
The checksum is supplied outside the JSON to avoid a circular self-checksum. Approved artifacts and
approval reports are retained in Git. A final artifact up to 900 KiB is delivered as an immutable
ConfigMap; a larger artifact uses a digest-pinned OCI data image and init container. Both forms mount
the same file path.

Risk and Matching load only at startup; there is no hot reload or intraday route change. An artifact
or identity mismatch blocks readiness. Gateway operational admission composes consumer readiness
and opens only after every required component agrees.

## Consequences

The runtime code, schemas, outbox connectors, topics, Risk projection, and native routing-policy
ingress become removal targets after startup loaders exist. Pure source normalization, validation,
calendar, tick, eligibility, and canonical-codec logic may be migrated into the offline builder.

The daily workflow and deployment become operational gates, but the trading hot path loses an
entire service and asynchronous projection lifecycle. The single artifact also makes Risk/Matching
configuration equality directly observable.

## Considered options

- Keeping runtime outbox/Kafka publication was rejected because daily controlled deployment does
  not justify the extra runtime consistency boundary.
- Yahoo Finance was rejected as the authority because official TWSE and TPEx sources are available.
- Splitting the artifact into per-partition files was rejected because Risk and every Matching must
  validate one authoritative envelope and identity.
- Gzip fallback was rejected to keep one direct file contract. Artifacts that exceed the ConfigMap
  safety threshold use OCI delivery.

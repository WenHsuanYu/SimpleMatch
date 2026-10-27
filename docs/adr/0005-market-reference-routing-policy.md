# Market Reference Routing Policy

Status: superseded by the offline daily-artifact architecture and retired by #119.

## Historical context

This ADR recorded an intermediate design. A runtime Market Reference publisher emitted a versioned
routing-policy artifact. Risk and Matching maintained local projections of that artifact. The
design kept artifact identity separate from the ingress `routingSnapshotId`. It required
deterministic instrument assignment and persisted an explicit partition before remote work.

Those invariants informed the replacement. The design never promised production compatibility for
the runtime publication and projection model.

## Current decision

The offline builder now owns pure normalization, Taiwan calendar, eligibility, tick-table,
deterministic assignment, checksum, and codec logic.
[`config/market-reference/README.md`](../../config/market-reference/README.md) documents the builder.
Its implementation is in `tools/market-reference-builder`. It produces one reviewed daily artifact.

Risk and Matching load the same immutable artifact at startup. Final commands and Open Barriers
carry its checksum, trading day, partition, and algorithm version. Missing, stale, incomplete, or
mismatched artifacts fail closed.

The repository removed the former runtime database, routing-policy schemas, outbox, Debezium
connector, Kafka topics, Risk projection, and native policy-ingress lifecycle. Git history retains
the historical implementation and verification evidence. Current certification must use the final
`matching.commands` and `matching.events` contracts.

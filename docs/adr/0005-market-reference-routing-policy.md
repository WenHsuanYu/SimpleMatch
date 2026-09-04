# Market Reference Routing Policy

Status: superseded by the offline daily-artifact architecture and retired by #119.

## Historical context

This ADR recorded an intermediate design in which a runtime Market Reference publisher emitted a
versioned routing-policy artifact and Risk/Matching projected it locally. It separated artifact
identity from the ingress `routingSnapshotId`, required deterministic instrument assignment, and
persisted an explicit partition before remote work. Those invariants informed the replacement, but
the runtime publication/projection boundary was never a production compatibility promise.

## Current decision

The pure normalization, Taiwan calendar, eligibility, tick-table, deterministic assignment,
checksum, and codec logic now belongs to the offline builder documented in
[`config/market-reference/README.md`](../../config/market-reference/README.md) and implemented in
`tools/market-reference-builder`. It produces
one reviewed daily artifact. Risk and Matching load the same immutable artifact at startup and
carry its checksum, trading day, partition, and algorithm version in the final command/Open Barrier
identity. Missing, stale, incomplete, or mismatched artifacts fail closed.

The former runtime database, routing-policy schemas, outbox, Debezium connector, Kafka topics,
Risk projection, and native policy-ingress lifecycle are removed. Historical implementation and
verification evidence remains in Git history; current certification must use the final
`matching.commands` and `matching.events` contracts.

# Phase 5 Market Reference Publisher Evidence

> Superseded by the offline daily-artifact design. This page preserves the historical decision and
> verification boundary for the former runtime publisher; it is not an executable runbook.

## Historical decision

The former Spring/PostgreSQL publisher normalized official Market Reference source documents,
persisted immutable snapshots, and emitted routing-policy events. That design was useful for
characterizing validation, Taiwan trading-calendar, eligibility, tick-table, checksum, and
idempotency behavior, but it introduced a runtime database, outbox, connector, and Kafka topic
between artifact approval and session startup.

The accepted replacement is the repository-owned offline builder documented at
[`config/market-reference/README.md`](../config/market-reference/README.md), implemented in
`tools/market-reference-builder`. It retains the
pure normalization, calendar, eligibility, tick, codec, checksum, and source-provenance logic and
emits one reviewed daily artifact. Risk and Matching load the same immutable artifact before the
trading session opens; there is no runtime Market Reference publication or projection path.

## Preserved invariants

The replacement continues to require complete and canonical source content, deterministic
instrument ordering, Taiwan time-zone/date handling, explicit partition assignment, stable content
checksums, and fail-closed behavior for missing, stale, incomplete, or mismatched artifacts. The
artifact checksum and trading day are part of the Matching command/Open Barrier identity, so a
runtime consumer cannot silently substitute a different source revision.

The old implementation's database/outbox and routing-policy event evidence is retained in Git
history only. New certification must use the offline builder's artifact and the final
`matching.commands`/`matching.events` contracts.

## Current verification boundary

Use the builder's own tests and the shared final-contract checks. The retired runtime service,
its Flyway migrations, connector, deployment, and service-specific integration commands are
intentionally absent from Gradle settings and Kubernetes overlays. A repository search for
`market-reference.snapshots` or `market-reference.routing-policies` must return no active runtime
producer or consumer.

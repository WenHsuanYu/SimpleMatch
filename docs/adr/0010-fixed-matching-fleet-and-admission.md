# Fence a fixed Matching fleet and centralize admission in one Gateway

Status: accepted.

## Context

Manual Kafka partition assignment keeps instrument ownership stable and avoids consumer-group
rebalance, but it does not by itself prevent two processes from consuming the same partition. The
trading system also needs one place to combine Market Reference identity, Matching recovery,
critical-consumer lag, quarantine, and session time into admission decisions.

The current QuickFIX StatefulSet has two replica slots but only one configured FIX session and
process-local ownership. It is unfinished scale-out scaffolding, not a safe multi-owner deployment.

## Decision

Phase 1 runs one QuickFIX Gateway and 15 native Matching pods. `matching-N` maps directly to
partition `N`. Each Matching ordinal is protected by StatefulSet identity, its own
`ReadWriteOncePod` PVC, and a renewable Kubernetes Lease. A pod cannot poll, replay, match, publish,
or become Ready without a valid infrastructure-owned `PartitionOwnershipPermit`. Lease uncertainty
for five seconds self-fences the process. Force deletion is prohibited by the normal recovery
runbook.

The Gateway starts `PRE_OPEN` and owns the admission state machine:

- `PRE_OPEN`: new and cancel closed.
- `OPEN`: new and cancel open.
- `NEW_ORDERS_PAUSED`: new closed, cancel open.
- `MARKET_INTERRUPTED`: new and cancel closed.
- `CLOSED`: terminal for the trading day.

Operators use `status`, `open`, `pause-new-orders`, `interrupt-market`, and `close-day`. Only `open`
can open admission and it always repeats the complete readiness validation. Session close
automatically closes admission; failure recovery never automatically reopens.

Infrastructure adapters collect Risk status, Matching pod/Lease/recovery status, Kafka end offsets,
and Persistence/Account/QuickFIX critical-consumer status. Gateway domain logic sees only
service-specific status values combined as `TradingSystemStatus`; it does not depend directly on
Kubernetes or Kafka client types.

State silence over five seconds pauses new orders. A critical consumer's oldest unprocessed record
warns at 30 seconds and pauses at 120 seconds. Zero market activity has no pending record and remains
Ready. Artifact, session, schema, algorithm, or event-payload identity conflict interrupts the
market and requires operator investigation. Market-data projection is non-critical and does not
participate in admission readiness.

Persistence, Account, and QuickFIX use independent critical consumer groups and durable inboxes.
Persistence stores immutable trades and two order-fill legs. Account atomically applies fill and
reservation transitions. QuickFIX stores event receipt and per-order delivery intent durably and
uses stable FIX execution identity with a JDBC-backed session message store. Market-data projection
is rebuildable and may use delayed retry/DLQ.

## Consequences

The Gateway becomes the single Phase 1 operational admission authority but not a new standalone
coordination service. Healthy Matching partitions continue existing work during a global new-order
pause; cancels remain accepted and wait durably for a recovering partition. No partition is
reassigned and no order book is added intraday.

Production Matching requires three integer CPUs per pod, Guaranteed QoS, CPU pinning/static CPU
Manager certification, and a capacity/recovery benchmark. Local development may run with reduced
durability and scheduling but cannot satisfy production readiness.

## Considered options

- Multiple QuickFIX owners were deferred because shared session ownership, fencing, and failover are
  not complete.
- Consumer-group rebalance was rejected because a failed partition must not move to another
  Matching process.
- A standalone Operational Coordination service was rejected; the single Gateway can compose the
  required operational facts through adapters.
- Kubernetes readiness alone was rejected because it does not express artifact identity, Kafka
  progress, quarantine, or deterministic recovery.

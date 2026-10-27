# Fence a fixed Matching fleet and centralize admission in one Gateway

Status: accepted.

## Context

Manual Kafka partition assignment keeps instrument ownership stable and avoids consumer-group
rebalance. It does not by itself prevent two processes from consuming the same partition. The
trading system also needs one place to combine operational facts for admission decisions:

- Market Reference identity.
- Matching recovery.
- Critical-consumer lag.
- Quarantine.
- Session time.

Before this decision, the QuickFIX StatefulSet declared two replica slots. It configured only one
FIX session and stored ownership inside each process. That incomplete scale-out design did not
provide a safe deployment with multiple owners.

## Decision

Phase 1 runs one QuickFIX Gateway and 15 native Matching pods. `matching-N` maps directly to
partition `N`. StatefulSet identity, a dedicated `ReadWriteOncePod` PVC, and a renewable Kubernetes
Lease protect each Matching ordinal. A pod requires a valid infrastructure-owned
`PartitionOwnershipPermit` before it can poll, replay, match, publish, or become Ready. The process
fences itself after five seconds of Lease uncertainty. The normal recovery runbook prohibits force
deletion.

The Gateway starts in `PRE_OPEN` and owns the admission state machine:

- `PRE_OPEN`: the Gateway rejects new orders and cancels.
- `OPEN`: the Gateway accepts new orders and cancels.
- `NEW_ORDERS_PAUSED`: the Gateway rejects new orders and accepts cancels.
- `MARKET_INTERRUPTED`: the Gateway rejects new orders and cancels.
- `CLOSED`: the Gateway remains closed for the rest of the trading day.

Operators use `status`, `open`, `pause-new-orders`, `interrupt-market`, and `close-day`. Only `open`
can open admission. It always repeats the complete readiness validation. Session close
automatically closes admission. Failure recovery never automatically reopens admission.

Infrastructure adapters collect Risk status, Matching pod/Lease/recovery status, Kafka end offsets,
and Persistence/Account/QuickFIX critical-consumer status. Gateway domain logic sees only status
values for those services, combined as `TradingSystemStatus`. It does not depend directly on
Kubernetes or Kafka client types.

State silence over five seconds pauses new orders. A critical consumer's oldest unprocessed record
triggers a warning at 30 seconds and a pause at 120 seconds. Zero market activity creates no pending
record and remains compatible with Ready status. An artifact, session, schema, algorithm, or
event-payload identity conflict interrupts the market. It requires operator investigation.

Market-data projection is non-critical and does not participate in admission readiness.
Persistence, Account, and QuickFIX use independent critical consumer groups and durable inboxes.
Persistence stores immutable trades and two order-fill legs. Account atomically applies fill and
reservation transitions.

QuickFIX durably stores event receipt and delivery intent for each order. It uses stable FIX
execution identity and a JDBC-backed session message store. Market-data projection is rebuildable
and may use delayed retry and a dead-letter queue (DLQ).

## Consequences

The Gateway becomes the single Phase 1 authority for operational admission. It does not become a
separate coordination service. Healthy Matching partitions continue existing work during a global
pause of new orders. Cancels remain accepted and wait durably for a recovering partition. The
system neither reassigns partitions nor adds order books intraday.

Production Matching requires three integer CPUs per pod and Guaranteed QoS. It also requires CPU
pinning/static CPU Manager certification and a capacity/recovery benchmark. Local development may
use reduced durability and scheduling guarantees, but cannot satisfy production readiness.

## Considered options

- This decision defers multiple QuickFIX owners because shared session ownership, fencing, and
  failover are incomplete.
- This decision rejects consumer-group rebalance. A failed partition must not move to another
  Matching process.
- This decision rejects a standalone Operational Coordination service. The single Gateway can
  combine the required operational facts through adapters.
- This decision rejects Kubernetes readiness as the only admission check. Kubernetes readiness does
  not express artifact identity, Kafka progress, quarantine, or deterministic recovery.

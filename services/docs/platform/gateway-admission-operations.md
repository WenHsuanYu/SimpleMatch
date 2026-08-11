# Gateway admission operations

The single Phase 1 QuickFIX Gateway is the only authority that decides whether new orders and
cancellations may enter the durable admission path. It is not a replacement for Risk, Matching, or
Kubernetes ownership: it combines their reported facts into one admission decision.

## Gate states

| State | New orders | Cancellations | Meaning |
| --- | --- | --- | --- |
| `PRE_OPEN` | rejected | rejected | Process start or incomplete operational evidence. |
| `OPEN` | accepted | accepted | Three fresh complete ready observations passed and an operator opened the session. |
| `NEW_ORDERS_PAUSED` | rejected | accepted | Existing orders may still be cancelled while a bounded recovery catches up. |
| `MARKET_INTERRUPTED` | rejected | rejected | Identity, topology, or deterministic-integrity evidence is contradictory. |
| `CLOSED` | rejected | rejected | Terminal state for this Gateway process's trading day. |

The FIX ingress gate applies these permissions before WAL and Risk submission. A pause is global to
new orders, not a reassignment of the affected Matching partition; healthy partitions drain their
already admitted commands while a recovering partition retains its backlog and cancellations in
`matching.commands`.

## Application commands

`GatewayOperationalCommandHandler` accepts exactly five transport-neutral operations:

```text
status
open
pause-new-orders
interrupt-market
close-day
```

Every changing command carries an operator identity and a non-empty reason. Its accepted or rejected
outcome, gate state, readiness, and time are retained in `quickfix_gateway.gateway_operation_audit`.
`open` requires three separately reported `OPEN_ELIGIBLE` observations; it cannot reopen after
`CLOSED`. Recovery becoming healthy does not issue an implicit open.

The application boundary is deliberately not a public unauthenticated endpoint. A secure CLI or
internal HTTP adapter, workload identity, RBAC, and network policy are deployment/security work;
they must call this handler rather than mutate `GatewayAdmissionGate` directly.

## Automatic safety actions

The scheduled monitor evaluates the most recent complete observation at least every second. It:

- pauses new orders if required status is more than five seconds old, a component is unavailable,
  a partition is unrecovered, or a required consumer has lag;
- records a warning at 30 seconds for the oldest pending critical event and requires a pause at 120
  seconds;
- interrupts the market for identity/schema/artifact/algorithm/image mismatch, invalid fixed Kafka
  topology, quarantine, or the same event ID with different raw payload bytes; and
- closes the session at 13:30 Asia/Taipei by default.

No Matching Event is required for readiness: an empty partition reports its committed offset equal to
its end offset and has no oldest pending-event age.

## Status input boundary

The evaluator accepts only `RiskStatus`, `MatchingFleetStatus`, `CriticalConsumerStatus`, and
`KafkaStatus`. Infrastructure adapters must normalize Kubernetes Lease/readiness data, Kafka end
offsets, and service-owned status endpoints into these values. Kubernetes and Kafka classes do not
enter the domain controller.

Until those live adapters are installed, the process has no complete observation and remains
`PRE_OPEN`; an operator cannot bypass that condition with `open`. Current capability evidence and
the remaining adapter/deployment work are recorded in the
[Phase 1 remaining-work inventory](../../../docs/routing-policy-remaining-work.md#go-1-operate-one-gateway-admission-authority).

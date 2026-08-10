# FIX Gateway Contract

This is the canonical target contract for SimpleMatch's external FIX 4.4 boundary. Current
implementation and migration status are tracked in the
[Phase 1 Trading Release remaining-work inventory](../../../docs/routing-policy-remaining-work.md).

## Boundary and command flow

`quickfix-gateway` is the single Phase 1 FIX Acceptor. It validates and normalizes new-order and
cancel requests, appends valid intent to its durable WAL, submits synchronously to `risk-service`,
and maps Matching Events back to the owning FIX session. It never owns risk policy, routing, order
books, matching, account authority, or permanent trade storage.

| Client intent | FIX message | Internal boundary |
| --- | --- | --- |
| New order | `NewOrderSingle` (`35=D`) | Durable Gateway intent followed by synchronous Risk admission and asynchronous `matching.commands` publication |
| Cancel | `OrderCancelRequest` (`35=F`) | Durable Gateway intent followed by synchronous Risk admission and asynchronous cancel command |
| Execution/order update | outbound `ExecutionReport` (`35=8`) | Critical consumption of `matching.events` |
| Cancel rejection | outbound `OrderCancelReject` (`35=9`) | Risk or Matching terminal outcome mapped to the original session |

The first successful `PendingNew` response is sent only after Risk durably commits admission. A
Gateway WAL is an ingress recovery representation, not the authoritative successful-admission or
Matching journal.

## Phase 1 session ownership

Phase 1 deploys exactly one Gateway replica with one configured set of FIX sessions. Existing
multi-replica StatefulSet and owner-routing artifacts are scale-out scaffolding, not evidence of
safe multi-owner operation. Distributed FIX ownership, standby promotion, and session-route
transfer require a future design and are not part of Phase 1 readiness.

The durable business idempotency key remains at least:

```text
SenderCompID + TargetCompID + TradingDay + MsgType + ClOrdID
```

Equivalent retransmission resolves to the same internal `commandId`; differing content for the same
identity is rejected as a conflict. A cancel has its own `ClOrdID` and references `OrigClOrdID`.

## Admission state machine

The Gateway starts `PRE_OPEN` and owns these states:

| State | New order | Cancel | Meaning |
| --- | --- | --- | --- |
| `PRE_OPEN` | closed | closed | Startup, artifact installation, or recovery is incomplete. |
| `OPEN` | open | open | Full readiness passed and an operator opened admission. |
| `NEW_ORDERS_PAUSED` | closed | open | Risk is bounded while existing orders may still be cancelled. |
| `MARKET_INTERRUPTED` | closed | closed | Identity or market-state correctness is uncertain. |
| `CLOSED` | closed | closed | Terminal state for the trading session. |

Operators use:

```text
status
open
pause-new-orders
interrupt-market
close-day
```

Only `open` can enter `OPEN`, and every call repeats full readiness checks. Session close
automatically closes admission; a per-request session-time check prevents scheduler delay from
admitting late work. Matching continues draining already admitted commands. Recovery never
automatically reopens.

## Operational readiness

Gateway infrastructure adapters collect Risk, all 15 Matching owners, Kafka, and critical-consumer
status. Domain logic receives `RiskStatus`, `MatchingFleetStatus`, `CriticalConsumerStatus`, and
`KafkaStatus`, then derives `TradingSystemStatus` without importing Kubernetes or Kafka types.

Opening requires the exact trading session, artifact, command/event schema, Matching algorithm,
image identities, 15 partition owners, successful recovery, no quarantine, and acceptable lag.
Status silence over five seconds pauses new orders. A critical consumer's oldest pending record
warns at 30 seconds and pauses at 120 seconds. No market activity means no pending record and does
not degrade readiness. Artifact, session, schema, algorithm, or same-event/different-payload conflict
interrupts the market.

## Durable Matching Event delivery

The Gateway consumes all 15 `matching.events` partitions in its own critical consumer group. It
stores the raw-value hash in a durable inbox and creates one delivery intent for each affected order
leg.

```text
deliveryId = deterministic(eventId + recipientOrderId)
```

A trade normally creates a maker and a taker Execution Report. Each report has a stable `ExecID`
derived from the trade/leg identity. Inbox receipt and delivery intents commit before the Kafka
offset. A failed record is retried in place or quarantined; it is never skipped to a normal DLQ.

Production QuickFIX sequence numbers and outbound messages use a JDBC-backed durable MessageStore.
The delivery ledger answers which business reports must be delivered; the QuickFIX MessageStore
answers which FIX messages and sequence numbers exist. Network delivery is at least once because a
crash can make the sender uncertain whether the peer received a message. Retransmission preserves
the same business `ExecID` and FIX session resend semantics so the peer can deduplicate it.

## Execution-report mapping

| Internal outcome | FIX response | Required semantics |
| --- | --- | --- |
| Durable admission pending Matching | `ExecutionReport` | `ExecType=PendingNew`, `OrdStatus=PendingNew` |
| Order resting | `ExecutionReport` | `ExecType=New`, `OrdStatus=New`, current cumulative/leaves values |
| Partial/full trade leg | `ExecutionReport` | `PartialFill`/`Fill`, stable `ExecID`, last/cumulative/leaves/average values |
| New-order rejection | `ExecutionReport` | `ExecType=Rejected`, `OrdStatus=Rejected`, stable reason |
| Successful cancel or expiry | `ExecutionReport` | `Canceled` or terminal status with original and current identities |
| Cancel rejection | `OrderCancelReject` | `ClOrdID`, `OrigClOrdID`, current `OrdStatus`, response type, reason, and text |

Maker/taker are Matching roles for one trade; Gateway maps each leg to the session that owns its
order. A repeated Matching Event with the same raw bytes creates no second delivery intent. The same
event identity with different bytes is quarantined and interrupts admission.

## Compatibility

FIX 4.4 and [`FIX44.xml`](../../../config/quickfix/fix-spec/FIX44.xml) are the external baseline. FIX
anti-corruption mappers, Gateway WAL schema versioning, and WAL-to-Risk translation remain legitimate
long-lived boundaries even after repository v1 Protobuf migration seams are removed. A
counterparty-specific tag or session rule requires its own versioned dictionary/configuration and
certification; it cannot silently alter the baseline.

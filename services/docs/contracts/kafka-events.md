# Kafka Event Contracts

This is the canonical target contract for cross-service Kafka records. Current implementation and
legacy-removal status are tracked in the
[Phase 1 Trading Release remaining-work inventory](../../../docs/routing-policy-remaining-work.md).

## Delivery model

Risk publishes Matching Commands through its transactional outbox after durable admission.
Matching consumes one explicitly assigned partition and publishes Matching Events directly from its
output publisher. Kafka delivery is at least once: a crash may repeat a record, but deterministic
identity and consumer-owned durable inboxes prevent duplicate local business effects.

The retired `orders.commands` Gateway publication is not a production path. The target also removes
`market-reference.snapshots` and `market-reference.routing-policies`; Risk and Matching load the
same approved file rather than consume runtime Market Reference topics.

## Topic catalogue

| Topic | Partition/key rule | Producer | Consumers | Purpose |
| --- | --- | --- | --- | --- |
| `matching.commands` | 15 fixed partitions; explicit artifact route; key=`commandId` | `risk-service` outbox | exactly one `matching-N` per partition | New order, cancel, Open Barrier, and Close Barrier inputs |
| `matching.events` | 15 fixed partitions aligned to input; key=`eventId` | `matching-N` | Persistence, Account, QuickFIX, market-data projection | Deterministic order and trade lifecycle facts |
| `account.lifecycle` | key=`accountId` | `account-service` outbox | account/audit projections | Reservation authority outcomes |
| `marketdata.events` | key=`venueMic:symbol` | market-data projection | `marketdata-streamer` | Rebuildable public market-data deltas |
| `audit.events` | owner-defined aggregate identity | owning service outbox | audit consumers | Optional append-only audit integration |

The record key supports tracing and producer behavior; it does not choose Matching ownership.
`risk-service` and `matching-N` set the numeric partition explicitly from the approved artifact and
pod ordinal. Consumers must not infer total order across partitions.

## Matching Command envelope

`MatchingCommand` has stable common metadata and one of these command types:

- `NEW_ORDER`
- `CANCEL_ORDER`
- `TRADING_DAY_OPEN_BARRIER`
- `TRADING_DAY_CLOSE_BARRIER`

Every command carries at least:

```text
schemaVersion
commandId
tradingDay
tradingSessionId
artifactId
partitionId
commandType
```

Order commands additionally carry the normalized order identity, account, instrument, side,
quantity, price semantics, and time-in-force required by Matching. `commandId` is created from the
durable ingress idempotency boundary and survives FIX, outbox, and Kafka redelivery. A repeated
business command never receives a fresh identity.

An Open Barrier appears after the final artifact is approved and loaded. It records the trading
session, artifact, partition, Matching algorithm version, event-schema version, identity version,
and pinned image digest. It defines the replay baseline. A Close Barrier is placed after Gateway
admission closes and Risk has drained prior admitted commands; it expires remaining ROD orders and
closes that partition deterministically.

## Matching Event envelope

`MatchingEvent` has stable common metadata and one of these Phase 1 event types:

- `ORDER_RESTED`
- `TRADE_EXECUTED`
- `ORDER_CANCELLED`
- `ORDER_EXPIRED`

Every event carries at least:

```text
schemaVersion
identityVersion
eventId
tradingDay
tradingSessionId
artifactId
partitionId
commandId
sourceInputOffset
outputIndex
eventType
```

`TRADE_EXECUTED` additionally carries `tradeId`, `matchIndex`, venue, symbol, aggressor side,
64-bit whole-share quantity, 64-bit price in 1/10,000 TWD units, and complete maker/taker legs. Each
leg includes order/account identity, cumulative fill, remaining quantity, and resulting state. One
trade event therefore creates one immutable trade and two order-fill legs downstream.

Maker and taker are roles for one trade. The maker was already resting in the book; the taker is the
incoming order that triggered the match. Either role may be buy or sell, and a partially matched
incoming order may later rest and become a maker.

## Deterministic identity and indices

`outputIndex` starts at zero for every command and counts all externally published events in the
exact order produced by the single Matching core. `matchIndex` separately counts only trades.
Neither is a Kafka offset or physical ring slot.

Conceptually:

```text
eventId = SHA-256(
  "simplematch.event-id.v1",
  tradingSessionId,
  partitionId,
  commandId,
  outputIndex)

tradeId = SHA-256(
  "simplematch.trade-id.v1",
  tradingSessionId,
  partitionId,
  commandId,
  matchIndex)
```

The implementation uses a specified fixed-width or length-delimited byte encoding, not ambiguous
string concatenation. The event type is deliberately absent from `eventId`: if replay assigns a
different payload to the same output slot, consumers must detect a deterministic violation rather
than accept a second identity.

The 256-bit identities travel as 32-byte wire/database values and render as 64 lowercase hex
characters in JSON and logs. Kafka source topic/partition/offset remains trace metadata, not
business identity; the same business command can be delivered at another input offset without
becoming a new command.

## Raw-value fingerprint

Every critical consumer computes:

```text
payloadSha256 = SHA-256(exact Kafka record value bytes)
```

The hash is not embedded in the record value. Consumers hash the raw bytes before parsing and store
the result beside `eventId` in their inbox. They do not parse and reserialize to compute it.

| Inbox observation | Result |
| --- | --- |
| New event ID | Apply the local transaction and save ID/hash. |
| Same event ID and same hash | Safe duplicate; no second business effect. |
| Same event ID and different hash | Deterministic violation; quarantine and interrupt. |

The producer uses one pinned C++ binary, schema, and deterministic serializer for the complete
trading session. The event value contains no wall-clock publication timestamp, random value, or
self-checksum. Operational age uses Kafka record append metadata, not a nondeterministic business
payload field. Protobuf maps are excluded from these records, and C++ golden record bytes must parse
in every Java critical consumer. Protobuf deterministic serialization is not treated as a
cross-version or cross-language canonical format.

## Matching publication and input commit

The Matching core writes events to the output ring followed by an internal
`CommandOutputCompleted` marker containing the command, input offset, and output count. The marker
is not published to Kafka and has no event identity.

The publisher enables idempotence and `acks=all`, sends every event to the same numeric output
partition, and tracks ACKs by input offset. An input becomes completed only after all its outputs
are ACKed. The offset coordinator commits only the highest contiguous completed input offset; it
never jumps across a missing ACK. Graceful shutdown drains rings, waits for ACKs, and performs a
final synchronous commit.

A crash before input commit may repeat events. Producer idempotence handles retry inside one
producer session; deterministic event identity and downstream inboxes handle replay across process
sessions. Phase 1 does not claim Kafka record-level exactly once and does not require Kafka
transactions.

## Consumer criticality

| Consumer | Policy |
| --- | --- |
| Persistence | Critical. Inbox/hash, immutable trade, two fills, and projections commit in one PostgreSQL transaction before offset commit. |
| Account | Critical. Inbox/hash, reservation/account transition, and lifecycle outbox commit atomically before offset commit. |
| QuickFIX | Critical. Inbox/hash and per-order delivery intents are durable before offset commit; delivery uses stable FIX identity and retry/quarantine. |
| Market-data projection | Non-critical and rebuildable. Delayed retry/DLQ is allowed without blocking critical consumers. |

A critical consumer never skips a failed earlier record to process a later record in the same
partition. Unknown schema, identity conflict, or same-ID/different-payload evidence is quarantined.
Ordinary PostgreSQL or consumer lag produces backpressure rather than a business rejection or DLQ.

## Production topic profile

Both Matching topics use:

```text
partitions=15
replication.factor=3
min.insync.replicas=2
cleanup.policy=delete
retention.ms=30 calendar days
unclean leader election=false
automatic topic creation=false
```

Producers use `acks=all` and idempotence. Neither topic is compacted. Production readiness validates
these values and certified disk headroom. A local one-broker profile may reduce replication for
development but cannot pass production readiness. The executable provisioning, validation, sizing,
and alert contract is in the
[Matching Kafka durability profile](../../../docs/kafka-matching-durability-profile.md).

## Evolution and compatibility

SimpleMatch has no external consumer or production history, so the coordinated repository cutover
does not preserve the legacy Matching topics or v1 payloads. Within one trading session, however,
schema, identity algorithm, Matching algorithm, and image digest are immutable. A new version starts
only with a later Open Barrier after every producer and critical consumer supports it. Historical
re-execution uses the original image; stored events remain the permanent integration facts.

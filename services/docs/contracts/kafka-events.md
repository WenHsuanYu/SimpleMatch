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
| `matching.events` | 15 fixed partitions aligned to input; raw 32-byte key=`eventId` | `matching-N` | Persistence, Account, QuickFIX, market-data projection | Deterministic order and trade lifecycle facts |
| `account.lifecycle` | key=`accountId` | `account-service` outbox | account/audit projections | Reservation authority outcomes |
| `marketdata.events` | key=`venueMic:symbol` | market-data projection | `marketdata-streamer` | Rebuildable complete last-trade and top-five snapshots |
| `audit.events` | owner-defined aggregate identity | owning service outbox | audit consumers | Optional append-only audit integration |

The record key supports tracing and producer behavior; it does not choose Matching ownership.
`risk-service` and `matching-N` set the numeric partition explicitly from the approved artifact and
pod ordinal. A `matching.events` consumer must verify that the actual Kafka record partition equals
the `partitionId` carried by the event before it applies a downstream state transition. Consumers
must not infer total order across partitions.

`marketdata.events` contains an independently usable `MarketDataSnapshot` Protobuf value. Each value
has the source Matching Event identity and Kafka provenance, the last trade when present, and the
complete top five bid and ask levels. Phase 1 deliberately publishes complete snapshots rather than
independent deltas, so a slow downstream subscriber can replace its view without first fetching a
missing earlier record.

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

`eventId` is exactly 32 SHA-256 bytes on the Protobuf wire. The `matching.events` Kafka key is the
same 32-byte value. Lowercase 64-character hexadecimal text is only a rendering for logs, JSON,
diagnostics, and test vectors; the Kafka key is not UTF-8 hexadecimal text.

`TRADE_EXECUTED` additionally carries one 32-byte `tradeId`, a trade-only `matchIndex`, venue,
symbol, aggressor side, one 64-bit whole-share quantity, one 64-bit price in 1/10,000 TWD units,
and complete maker/taker legs. Trade quantity and trade price have one source of truth at the trade
level. Each leg carries order identity, account identity, side, cumulative filled quantity,
remaining quantity, average price, and explicit resulting state. One trade event therefore creates
one immutable trade and two order-fill legs downstream.

Maker and taker are roles for one trade. The maker was already resting in the book; the taker is the
incoming order that removed resting liquidity. The aggressor side is the taker's side for that
trade. Either role may be buy or sell, and a partially matched incoming order may later rest and
become a maker.

## Deterministic identity and indices

`outputIndex` starts at zero for every command and counts all externally published events in the
exact order produced by the single Matching core. `matchIndex` separately counts only trades and is
present only in `TRADE_EXECUTED`. Neither index is a Kafka offset or physical ring slot.

V1 identities use one normative binary preimage. `len32be(text)` is a four-byte unsigned big-endian
UTF-8 byte length, `int32be(value)` is a four-byte big-endian integer, and `commandId[16]` is the raw
16-byte UUID representation obtained from canonical UUID text.

```text
eventIdPreimage =
    len32be("simplematch.event-id.v1")
 || UTF8("simplematch.event-id.v1")
 || int32be(identityVersion)
 || len32be(tradingSessionId)
 || UTF8(tradingSessionId)
 || int32be(partitionId)
 || commandId[16]
 || int32be(outputIndex)

eventId = SHA-256(eventIdPreimage)

tradeIdPreimage =
    len32be("simplematch.trade-id.v1")
 || UTF8("simplematch.trade-id.v1")
 || int32be(identityVersion)
 || len32be(tradingSessionId)
 || UTF8(tradingSessionId)
 || int32be(partitionId)
 || commandId[16]
 || int32be(matchIndex)

tradeId = SHA-256(tradeIdPreimage)
```

For identity version 1, every integer input is non-negative and encoded in exactly four bytes.
Namespace text and the numeric identity version are both part of the preimage. Event type is
deliberately absent from `eventId`: if replay assigns a different payload to the same command output
slot, consumers must detect a deterministic violation instead of accepting a second identity.
Kafka source topic/partition/offset is also absent from identity and remains trace metadata.

Known V1 vectors for trading session `2026-08-11-regular`, partition `0` are:

| Identity | Command | Index | Lowercase hexadecimal rendering |
| --- | --- | ---: | --- |
| event | `0198a001-0000-7000-8000-000000000001` | output 0 | `2c7124e857ca6895c0d58a18341233768def54a5d9f24bbd0f7e7b08e8bb4873` |
| event | `0198a001-0000-7000-8000-000000000002` | output 0 | `436c95c15c97744324aaaf0cfd6cd27b371839e944df9ae40ebab37a207cbb6f` |
| trade | `0198a001-0000-7000-8000-000000000002` | match 0 | `033ec379a4a4f1b3b6e5826b4a31731304662b0647e412e59b4abe21afc3241b` |
| event | `0198a001-0000-7000-8000-000000000003` | output 0 | `ff5b587caaa9a87bafcb1b293d4e63c27a74442f719b35125fef840c36acae30` |
| event | `0198a001-0000-7000-8000-000000000005` | output 0 | `2baddf37ff38053a0d4b69a5359e152e9e83e4c3ac531f7d705d21de286b2a04` |

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
payload field. Protobuf maps are excluded from these records.

Deterministic Protobuf serialization is a repeatability mechanism for the pinned native producer;
it is not a canonical cross-version or cross-language serialization format. Cross-language
interoperability is verified by passing exact C++ record values into Java contract and critical
consumer seams, not by requiring Java serialization to reproduce the C++ bytes.

The repository pins these native raw records and their exact value hashes:

| Event type | Fixture | Raw-value SHA-256 |
| --- | --- | --- |
| `ORDER_RESTED` | `cpp-matching-order-rested-v1.hex` | `d4cf05dfa07dec2f54b55db4c793a3866539bcb3b2a5b6554722dc0719fafd94` |
| `TRADE_EXECUTED` | `cpp-matching-trade-executed-v1.hex` | `f263bd42b276005f17556ea3c1fbe5a998c6c1d438521cc5feb1b5c147087a27` |
| `ORDER_CANCELLED` | `cpp-matching-order-cancelled-v1.hex` | `0c63b3b0e3e952d674fa53b604e2c590cde9216a3bb20348d4f669fa6a08b4dd` |
| `ORDER_EXPIRED` | `cpp-matching-order-expired-v1.hex` | `8362a116785571de011a8fe53734adabc97735e9fa899063ff10dff117bf6b7c` |

## Matching publication and input commit

The Matching core writes events to the output ring followed by an internal
`CommandOutputCompleted` marker containing the command, input offset, and output count. The marker
is not published to Kafka and has no event identity.

The publisher enables idempotence and `acks=all`, sends every event to the same numeric output
partition carried by the final record, and tracks ACKs by input offset. An input becomes completed
only after all its outputs are ACKed. The offset coordinator commits only the highest contiguous
completed input offset; it never jumps across a missing ACK. Graceful shutdown drains rings, waits
for ACKs, and performs a final synchronous commit.

A crash before input commit may repeat events. Producer idempotence handles retry inside one
producer session; deterministic event identity and downstream inboxes handle replay across process
sessions. Phase 1 does not claim Kafka record-level exactly once and does not require Kafka
transactions.

For deterministic replay, the same original Matching Commands, original source offsets, pinned
trading session, Artifact identity, schema version, identity version, Matching algorithm, and image
must reproduce the same ordered `{key, partition, value}` records byte for byte. The source offset is
trace metadata but remains part of the event value, so replay uses the original retained command
record and its original offset.

## Consumer criticality

| Consumer | Policy |
| --- | --- |
| Persistence | Critical. Inbox/hash, immutable trade, two fills, and projections commit in one PostgreSQL transaction before offset commit. |
| Account | Critical. Inbox/hash, reservation/account transition, and lifecycle outbox commit atomically before offset commit. |
| QuickFIX | Critical. Inbox/hash and per-order delivery intents are durable before offset commit; delivery uses stable FIX identity and retry/quarantine. |
| Market-data projection | Non-critical and rebuildable. Delayed retry/DLQ is allowed without blocking critical consumers. |

A critical consumer validates schema, deterministic identity, raw Kafka key, and Kafka partition
before applying the local transaction. It never skips a failed earlier record to process a later
record in the same partition. Unknown schema, identity conflict, key mismatch, partition mismatch,
or same-ID/different-payload evidence is quarantined. Ordinary PostgreSQL or consumer lag produces
backpressure rather than a business rejection or DLQ.

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

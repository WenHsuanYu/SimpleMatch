# Matching Events V1 implementation record

This document records the implementation completed for issue #129. It explains
where the final `matching.events` contract is enforced, why those responsibilities
are placed there, and which verification evidence protects the contract. The
canonical wire and delivery requirements remain in
[`kafka-events.md`](kafka-events.md).

## Module and seam ownership

The deterministic Matching Core Module owns order-book state transitions and
semantic matching results. Its Interface accepts decoded Matching Commands and
exposes the resulting semantic events. It does not derive Kafka record identity,
serialize Protobuf values, or depend on Kafka types. Keeping integration identity
outside this Module preserves locality for matching rules and allows the same
semantic result to be exercised without transport infrastructure.

`MatchingEventEncoder` is the final-record Module at the publication seam. Its
small Interface accepts a `MatchingCommandContext`, the original input offset,
and one semantic `CoreEvent`, and returns one complete `MatchingEventRecord`. Its
Implementation hides deterministic event and trade identity derivation,
event-specific contract validation, Protobuf construction, deterministic
serialization, and Kafka record construction. These responsibilities are kept
behind one Interface because every publisher call must apply the same rules.

`MatchingEventPublisher` remains the publication port. The
`RdkafkaMatchingEventPublisher` production Adapter preserves the durability
profile established by issue #125: explicit numeric partition, producer
idempotence, and `acks=all`. Issue #129 does not introduce another publication
abstraction or duplicate the producer durability policy.

On the Java side, `FinalMatchingEventEnvelope.parse(byte[])` is the shared
contract seam and the only construction path for a validated final event. It
computes SHA-256 over the exact Kafka record value before Protobuf parsing,
validates schema and event semantics, and independently recomputes V1 event and
trade identities. A caller cannot pair parsed semantics from one event with raw
bytes or a fingerprint from another event.

`FinalMatchingEventTransportValidator` is the shared transport-invariant seam.
It owns the two requirements that every `matching.events` consumer must apply:
the Kafka key must equal the raw 32-byte `eventId`, and the numeric Kafka
partition must equal the payload `partitionId`. Consumer Adapters retain their
own delivery, retry, quarantine, and transaction behavior instead of sharing a
consumer superclass or messaging framework.

Persistence, Account, QuickFIX, Market Data, and Query invoke that transport
validator before applying final-event effects. Account additionally translates
the validated Protobuf value at its Kafka seam into
`FinalMatchingEventAccountCommand` and `MatchingAccountEffect` values. Those
effects use Account-owned identifier, instrument, quantity, and price value
objects rather than carrying transport strings into business behavior.

For trade effects, Account also translates each Matching leg's explicit
resulting state into an Account-owned `ResultingState`. After a fill is applied,
the Account application service verifies that `PARTIALLY_FILLED` corresponds to
an active reservation and `FILLED` corresponds to an applied reservation. A
disagreement fails the transaction so the inbox claim, authority mutation, and
consumer progress roll back together.

## Deterministic identity

V1 event and trade identities are SHA-256 digests over an unambiguous binary
preimage. Text values are UTF-8 with a four-byte unsigned big-endian length;
integer values are four-byte big-endian values; the command UUID is its raw
16-byte representation. The namespace and numeric identity version are both in
the preimage. Event type and Kafka source offset are deliberately excluded.

The wire representation is the 32-byte digest. Lowercase 64-character
hexadecimal text is used only for logs, JSON, database diagnostics, and test
vectors. The Kafka `matching.events` key is the same raw 32-byte `eventId`, not
its hexadecimal rendering.

## Final event semantics

The final contract publishes `ORDER_RESTED`, `TRADE_EXECUTED`,
`ORDER_CANCELLED`, and `ORDER_EXPIRED`. `outputIndex` orders every externally
published result of one Matching Command. `matchIndex` exists only inside a
trade and orders trades produced by that command.

A `TRADE_EXECUTED` value contains one trade identity, instrument, aggressor
side, whole-share quantity, price in 1/10,000 TWD units, and complete maker and
taker order transitions. Trade quantity and price have one source of truth at
the trade level. Each leg carries order identity, account identity, side,
cumulative filled quantity, remaining quantity, average price, and explicit
resulting state. Downstream contexts therefore consume the state produced by
Matching instead of reconstructing it from remaining quantity.

## Duplicate and conflict behavior

Every critical consumer stores `eventId` together with the SHA-256 digest of
the exact Kafka value bytes. A previously unseen identity is applied. The same
identity with the same raw-value digest is an idempotent replay and causes no
second business effect. The same identity with a different raw-value digest is
a deterministic contract violation and is quarantined at the exact Kafka
partition and offset.

The Kafka source offset remains provenance. Replay uses the original retained
command record and therefore the original source offset in the event value. A
redelivered command that is already represented by committed runtime state does
not create a second publication with a new source offset.

## Replay guarantee

For the same original Matching Commands, original source offsets, pinned
trading session, artifact identity, schema version, identity version, matching
algorithm, and executable image, a fresh runtime must reproduce the same ordered
`{key, partition, value}` records byte for byte. Native tests execute the same
command sequence through fresh Matching Core and encoder instances and compare
the complete records rather than parsed semantic equality.

Deterministic Protobuf serialization is used to make one pinned native producer
repeatable. It is not treated as a canonical serialization format across
languages or Protobuf versions. Cross-language interoperability is demonstrated
in the opposite direction: Java critical consumers parse and validate exact raw
records emitted by the native producer.

## Verification evidence

The native contract suite pins one raw record for every final event type:

| Event type | Fixture | Raw-value SHA-256 |
| --- | --- | --- |
| `ORDER_RESTED` | `cpp-matching-order-rested-v1.hex` | `d4cf05dfa07dec2f54b55db4c793a3866539bcb3b2a5b6554722dc0719fafd94` |
| `TRADE_EXECUTED` | `cpp-matching-trade-executed-v1.hex` | `f263bd42b276005f17556ea3c1fbe5a998c6c1d438521cc5feb1b5c147087a27` |
| `ORDER_CANCELLED` | `cpp-matching-order-cancelled-v1.hex` | `0c63b3b0e3e952d674fa53b604e2c590cde9216a3bb20348d4f669fa6a08b4dd` |
| `ORDER_EXPIRED` | `cpp-matching-order-expired-v1.hex` | `8362a116785571de011a8fe53734adabc97735e9fa899063ff10dff117bf6b7c` |

The C++ encoder tests compare final record bytes with these independent fixture
literals and pin known event and trade identity vectors. The shared Java
contract suite parses all four records, pins their exact raw-value hashes, and
verifies the shared Kafka key and partition invariant. Persistence, Account, and
QuickFIX consumer tests feed the same native trade record through their public
Kafka Adapter seams and their real application transactions, then verify the
resulting durable facts or delivery progress before Kafka acknowledgment.

The Account interoperability test also changes a native trade leg from `FILLED`
to a valid `PARTIALLY_FILLED` representation while keeping the trade quantity
large enough to finish the local reservation. The Account transaction rejects
that semantic disagreement and rolls back all local effects before the record
is quarantined.

The `simplematch.contract-test-fixtures` build convention is the single place
that exposes shared contract resources to service tests. Service build files do
not depend on another Module's internal test-resource path. The obsolete generic
`cpp-matching-event-v1.hex` alias is removed; the event-specific fixture names
are the only native Matching Event test vectors.

Existing transaction-level inbox tests retain the duplicate and conflict
coverage: exact replay produces no second durable effect, while the same event
identity with different raw bytes fails closed. Existing issue #125 tests retain
publication durability coverage rather than duplicating Kafka producer
configuration tests in this change.

## Architectural record

No ADR is added for this implementation. The identity representation,
partitioning rule, deterministic replay requirement, and critical-consumer
behavior were already selected by the accepted issue and canonical contract.
This work finalizes and verifies those existing decisions rather than choosing a
new hard-to-reverse architectural alternative.

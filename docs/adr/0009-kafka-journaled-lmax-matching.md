# Use Kafka-journaled native single-writer Matching

Status: accepted.

## Context

Before this decision, the repository had a small native foundation for routing and quarantine
at ingress. It had no order book, Kafka runtime, deterministic recovery, or execution publisher.
An earlier proposal placed a local fsynced command/result journal in the Matching hot path.
That journal would add disk latency and duplicate durability that the partitioned, replicated
input log already provides.

The target must preserve deterministic price-time behavior. It must keep network and storage I/O
outside the single-core Matching path.

## Decision

Each `matching-N` explicitly owns `matching.commands` partition `N`. That Kafka partition is the
authoritative, durable, ordered input journal. A `TRADING_DAY_OPEN_BARRIER` defines the daily replay
baseline. A `TRADING_DAY_CLOSE_BARRIER` deterministically expires ROD orders and closes the partition.
A small PVC baseline serves as an acceleration index. Kafka remains authoritative.

The native C++ process uses an LMAX-style layout. Each preallocated ring has a single producer and
a single consumer (SPSC):

```text
Kafka ingress -> preallocated SPSC input ring -> single Matching core
              -> preallocated SPSC output ring -> Kafka publisher/coordinator
```

The core owns every mutable order book in its partition. It uses no locks, network I/O, disk I/O,
or allocation after warmup. Rings never drop records, overwrite records, or expand. Ingress pauses
when the input ring is full. The core waits when the output ring is full. Sustained high occupancy
triggers Gateway backpressure.

Output delivery is at least once. The publisher enables Kafka idempotence and `acks=all`. It tracks
every output for each input command. It marks an input complete only after acknowledgments (ACKs)
for all outputs. The offset coordinator commits only the highest contiguous completed input offset.

A crash between output ACK and input commit may replay identical events. Downstream inboxes
prevent duplicate business effects.

Recovery replays from the Open Barrier through the committed boundary. It does not publish those
completed effects again. It then processes uncommitted input normally. If the PVC baseline is
unavailable, Matching locates the retained Open Barrier in Kafka. Missing retained input fails
closed. Periodic order-book snapshots remain deferred unless full-day replay misses the accepted
recovery service-level objective (SLO).

Each command has a stable `commandId`. The core assigns `outputIndex` across all outputs and
`matchIndex` across trade outputs. Event and trade identities derive deterministically from the
trading session, partition, command, and corresponding index. The event type is not part of
`eventId`. Consumers store a SHA-256 of the exact bytes in each Kafka record value. The same
identity with different bytes is a deterministic violation, not a second event.

One trading session pins the Matching binary, algorithm version, event schema, and identity
version. A pod restart uses the same image digest. Upgrades occur only at a later Open Barrier.

## Consequences

PostgreSQL does not recover the order book. Matching never writes PostgreSQL. Asynchronous
consumers own permanent trade storage. Kafka retention, broker durability, replay tests, command
deduplication capacity, and deterministic binary deployment become correctness requirements.

Measure core latency and Kafka end-to-end latency separately. Microsecond-level latency is a
design target. Do not claim production latency before the benchmark and recovery certification
pass.

## Considered options

- This decision rejects a local fsync journal for each command. It duplicates Kafka durability and
  adds storage I/O to the latency-sensitive path.
- This decision defers Kafka transactions for Phase 1. Deterministic at-least-once replay and
  downstream inboxes provide the required business idempotency with less coordination in the hot
  path.
- This decision defers periodic order-book snapshots until full-day replay shows that they are
  necessary.
- This decision rejects Java/JNI Matching. It selects a native C++ implementation with preallocated
  rings and one owner of mutable state.

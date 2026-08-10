# Use Kafka-journaled native single-writer Matching

Status: accepted.

## Context

The repository has a small native routing/quarantine ingress foundation but no order book, Kafka
runtime, deterministic recovery, or execution publisher. An earlier proposal placed a local
fsynced command/result journal in the Matching hot path. That would add disk latency and recreate a
durability responsibility already provided by the partitioned replicated input log.

The target must preserve deterministic price-time behavior while keeping network and storage I/O
off the single-core Matching path.

## Decision

Each `matching-N` explicitly owns `matching.commands` partition `N`. That Kafka partition is the
authoritative durable ordered input journal. A `TRADING_DAY_OPEN_BARRIER` defines the daily replay
baseline and a `TRADING_DAY_CLOSE_BARRIER` deterministically expires ROD orders and closes the
partition. A small PVC baseline is an acceleration index; Kafka remains authoritative.

The native C++ process uses an LMAX-style layout:

```text
Kafka ingress -> preallocated SPSC input ring -> single Matching core
              -> preallocated SPSC output ring -> Kafka publisher/coordinator
```

The core owns every mutable order book in its partition and performs no locks, network/disk I/O, or
post-warmup allocation. Rings never drop, overwrite, or expand. Ingress pauses when the input ring
is full; the core waits when output is full. Sustained high occupancy triggers Gateway
backpressure.

Output publication is at least once. The publisher enables Kafka idempotence and `acks=all`, tracks
every output for each input command, and marks an input completed only after all outputs are ACKed.
The offset coordinator commits only the highest contiguous completed input offset. A crash between
output ACK and input commit may replay identical events; downstream inboxes remove duplicate
business effects.

Recovery replays from the Open Barrier through the committed boundary without re-publishing those
completed effects, then processes uncommitted input normally. If the PVC baseline is unavailable,
Matching locates the retained Open Barrier in Kafka. Missing retained input fails closed. Periodic
order-book snapshots are deferred unless full-day replay misses the accepted recovery SLO.

Each command has a stable `commandId`. The core assigns `outputIndex` across all outputs and
`matchIndex` across trade outputs. Event and trade identities derive deterministically from the
trading session, partition, command, and corresponding index. The event type is not part of
`eventId`; consumers store a SHA-256 of the exact Kafka record value bytes so that the same identity
with different bytes becomes a deterministic violation instead of a second event.

The Matching binary, algorithm version, event schema, and identity version are pinned for one
trading session. A pod restart uses the same image digest. Upgrades occur only at a later Open
Barrier.

## Consequences

PostgreSQL does not recover the order book, and Matching never writes PostgreSQL. Permanent trade
storage is an asynchronous consumer concern. Kafka retention, broker durability, replay tests,
command deduplication capacity, and deterministic binary deployment become correctness
requirements.

Core latency and Kafka end-to-end latency are measured separately. Microsecond-level latency is a
design target, not a production claim before the benchmark and recovery certification passes.

## Considered options

- A per-command local fsync journal was rejected because it duplicates Kafka durability and adds
  storage I/O to the latency-sensitive path.
- Kafka transactions were deferred for Phase 1 because deterministic at-least-once replay plus
  downstream inboxes provides the required business idempotency with less hot-path coordination.
- Periodic order-book snapshots were deferred until full-day replay demonstrates that they are
  necessary.
- Java/JNI Matching was rejected in favor of a native C++ implementation with preallocated rings
  and one mutable-state owner.

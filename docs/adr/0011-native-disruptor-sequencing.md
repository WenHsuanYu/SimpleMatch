# Native C++ Disruptor sequencing for Matching

Status: implementation in progress; supplements accepted ADR 0009.

## Problem and scope

ADR 0009 correctly requires a single owner of each mutable order book and two
preallocated, non-overwriting, single-producer/single-consumer (SPSC) directions.
The current `BoundedSpscRing` implements a useful transport, but it is not a
complete LMAX Disruptor: it has no separately claimed/published sequence,
multi-producer sequencer, dependency barriers, multiple gating consumers,
or event-processor graph. The Supervisor also has a distinct multi-threaded
control-plane state machine; single-writer book ownership does not serialize
its failure, shutdown, and backpressure transitions.

## Required interfaces and invariants

1. A cache-line-separated, monotonic 64-bit `Sequence` begins at `-1` and
   carries an explicit release/acquire publication protocol. A bounded ring
   has power-of-two capacity, fixed storage, and no per-event allocation.
2. `SingleProducerSequencer` claims from exactly one producer. The multi-
   producer sequencer uses atomic claims and per-slot availability flags;
   a later published slot never exposes an earlier unpublished hole.
3. Claiming uses the minimum of all terminal consumer gating sequences.
   A slow terminal consumer prevents overwrites, even when fast consumers
   have already processed an event. No consumer may touch a recycled slot.
4. `SequenceBarrier` limits visible events by both publication and declared
   predecessor sequences. Fan-out and dependency chains must be testable
   without reading incomplete event data.
5. Wait strategies include busy spin, yielding, and a bounded blocking
   strategy. Halt and shutdown must release waiters without inventing events.
6. A batch processor advances its sequence only after a successful handler;
   an exception must fail closed and never acknowledge the failed event.
7. Producer slots are always published after successful initialization;
   construction failures must not create permanent sequence holes. C++
   ownership and exception guarantees are stricter than a Java object ring.
8. Kafka remains the authoritative ordered journal. Input sequence and
   output sequence are local, volatile transport cursors, not Kafka offsets
   or durable publication ACKs. No event byte, identity, replay, Lease fencing,
   or publication-before-commit contract may change.

## Matching graph

```text
Kafka ingest/replay owner -> input sequencer -> single Matching writer
single Matching writer -> output sequencer -> publisher/coordinator
```

The two directional rings are a **Matching-specific topology**, not a
requirement of the Disruptor algorithm. For the existing single-ingress and
single-writer topology, single-producer sequencers are preferable to a
multi-producer CAS hot path. Multi-producer support is a reusable capability,
not authorization for multiple threads to call the current SPSC ingress.

## Alternative: one ring with dependent stages

The [LMAX Disruptor user guide](https://lmax-exchange.github.io/disruptor/user-guide/)
supports one ring with dependent event processors. A valid pipeline is:

```text
Kafka ingress producer -> command/result slot in one ring
                           -> Matching processor (matching_done)
                           -> Publishing processor (publisher_done)
                           -> Kafka delivery/ACK tracking (separate ledger)
```

The input producer must gate on the terminal `publisher_done` sequence.
The publisher must depend on `matching_done`. The Matching stage may update
its own slot only after it acquires the ingress publication. The publisher
may read those results only after it acquires `matching_done`. The ring must
keep the slot until the publisher copies all results to independently owned
pending-publication storage or completes their delivery. Neither stage may
keep a reference into an overwritten slot.

The prototype provides `RingBuffer::at_for_stage()` for the owning Matching
stage. This accessor checks only the slot's engagement. It does not establish
publication, exclusive ownership, or a consumer gate. Those conditions must
come from the caller's dependency graph and lifetime management. The
processor still exposes a read-only event reference by default.

The Matching Core emits a variable number of events per command. Each input
also needs one `RuntimeEndOfInput` marker. The high template's maximum is
`150 * 2 * 4096 + 1 = 1,228,801` Core events, plus the end marker.
Allocating a worst-case output array in every command slot would require
extensive memory. On the local GCC build, `sizeof(std::optional<RuntimeOutput>)`
is 280 bytes. An inline worst-case array would require about 82 MiB per
command slot for the local template and 328 MiB per slot for the high
template, before input and other ring overhead. These are type-size
calculations, not measured RSS. The existing Core event reserve alone
represents about 291 MiB of virtual storage for the high template, as recorded in
[Issue 217 capacity measurements](../verification/matching-capacity-issue-217.md).
Keeping only a reference to `Core::events()` is unsafe: processing the next
command clears that storage. A production one-ring design would therefore
need a separately owned bounded output-batch pool or would block the Core
until the publisher finishes the prior command.

An in-memory `publisher_done` cursor is not Kafka ACK. The Kafka offset
ledger must continue to wait for all event delivery ACKs before committing
the highest contiguous completed input offset. The one-ring example does not
implement that adapter, Lease fencing, publication retries, or replay.
No production topology change is accepted by this ADR. Keep the two SPSC
directions from ADR 0009 until a one-ring alternative demonstrates equivalent
correctness, memory bounds, and acceptable latency under source-aligned tests.

## Implementation status and safe use

The additive C++20 header currently implements single- and multi-producer
sequencers, fixed rings, RAII claims, barriers, three wait modes, shared
failure state, and batch event processors. Focused tests exercise sequencing,
gating, fan-out, failure, and a two-direction Matching example. This code does
not replace the production `MatchingRuntime` rings.

Callers must keep consumer `Sequence` objects alive until the owning ring is
destroyed. They must also keep rings alive until all claims and processors
finish. `RingBuffer::at()`, `at_for_stage()`, and `has_event()` do not synchronize an arbitrary
reader. A caller must first observe publication with acquire semantics and
must hold a live consumer gate until it finishes reading that slot.

All dependent barriers and processors must share one `PipelineFailure` object.
The current constructors permit omission for independent pipelines. A shared
failure stops subsequent event processing when a processor observes it. A
failure can race with an acknowledgement already in progress. This module
does not provide a global atomic failure-and-acknowledgement transaction.

The two-ring example is not a safe replacement for `MatchingRuntime`.
Production processing checks capacity for the maximum Core event burst and
the end marker **before** it mutates the order book. The example now checks
that headroom under one output producer. It fails closed on insufficient
headroom instead of preserving normal backpressure and retry semantics.
`available_to_write()` is stable against new claims only for a single owning
producer. It is advisory when several producers claim concurrently. An input
consumer sequence records in-memory progress, not Kafka publication ACK or
offset commit.

Capacity checks also stop at the final signed 64-bit sequence number. The
sequencer does not wrap its absolute sequence ID or reuse a negative ID.

Before production migration, implement output-capacity admission, preserve
`RuntimeEndOfInput` framing, and prove one producer per SPSC direction.
Verify Kafka publication-before-commit, deterministic event bytes, replay,
ownership fencing, and failure recovery against the existing runtime tests.
The remaining generic parity work includes batch claims, explicit dependency
graph lifecycle, stronger consumer-access guarantees, and bounded concurrency
stress with ThreadSanitizer evidence where the toolchain supports it.

## Verification and adoption gates

- RED/GREEN public-interface tests for wraparound, slow-consumer gating,
  multi-producer out-of-order publication, fan-out, dependency barriers,
  wait/halt, and failed event handling.
- Native and sanitizer CTest; bounded concurrency stress and ThreadSanitizer
  when a compatible build is available. Do not infer absence of data races
  from ASan/UBSan alone.
- Preserve local capacity (150 books x 2 sides x 1024 orders), event byte
  digests, deterministic replay and published-before-commit behavior.
- Prefer an additive, measured adoption path. Do not replace a production
  SPSC transport or claim performance parity until source-aligned benchmarks
  and a complete-local verification pass exist.
- Staging/production remain offline configuration templates under the
  amended Issue #217; no staging/production performance certification is
  required to develop the generic sequencer.

## Remaining decision

The project currently commits only to ADR 0009's LMAX-*style* SPSC topology.
Upgrading it to a full producer/consumer dependency graph requires a design
decision on where extra consumers sit and whether any must gate the writer.
The generic primitives must not silently introduce a new Kafka delivery or
transactional boundary.

Local verification: [Native Disruptor sequencing, 2026-11-19]
(../verification/native-disruptor-sequencing-20261119.md).

Single-ring feasibility verification: [Native Disruptor one-ring pipeline,
2026-11-19](../verification/native-disruptor-one-ring-20261119.md).

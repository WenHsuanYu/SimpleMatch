# Native C++20 Disruptor versus LMAX Java 4.0.0

Date: 2026-11-19. Source revision: `5c6c85ad` (`master`).

## Decision

**The difference is both an intentional scope choice and incomplete generic functionality.**
SimpleMatch has adopted the Disruptor sequencing model for its two production
Matching rings. It has not implemented every public API or behavior in Java 4.0.0.
The application needs bounded, single-producer ingress and output channels.
It does not currently require a general-purpose Disruptor framework.
Missing optional APIs are real feature gaps if the goal is Java API parity.
They do not, by themselves, establish a missing Matching business requirement.

This is a *feature inventory*, not a binary compatibility, concurrency
certification, or performance-parity claim. The first-party LMAX website labels
its current guide and Javadoc `4.0.0-SNAPSHOT`. The [4.0.0 changelog][change]
identifies released version-specific changes. Compare those sources with the
[official Java API][api], [User Guide][guide], and the pinned local files below.

## Feature and use matrix

The **C++ generic** column describes `disruptor.hpp` and `spsc_channel.hpp`.
**Production** describes actual `MatchingRuntime` and the Kafka completion
channel at this source revision. "Absent" means there is no matching generic
C++ API; it does not imply a defect in the current Matching topology.

| Java Disruptor 4.0.0 feature | C++ generic | Production use | Classification and local evidence |
| --- | --- | --- | --- |
| `Sequence`, cache-line isolation, single-producer sequencer, gating and wrap prevention ([guide][guide]) | **Implemented** | **Yes** | `disruptor.hpp:26-46,59-82,114-204`; `spsc_channel.hpp:19-22,62-66`; `matching_runtime.hpp:79-85`. The two Matching rings use `SingleProducerSequencer`. |
| Multi-producer claims, visibility of published slots and out-of-order completion ([guide][guide]) | **Implemented** | **No** | `disruptor.hpp:208-278` and `disruptor_sequencing_test.cpp:243-315`. Excluded intentionally from the single-ingress, single-writer production topology. This does not make coordinator input IDs or Kafka offsets safe for multiple ingress threads. |
| Fixed-capacity `RingBuffer`, nonblocking publication, consumer-gated reclamation ([ring][ring]) | **Implemented, different ownership model** | **Yes** | `disruptor.hpp:282-415` uses `vector<optional<T>>` with RAII claim and per-slot construction. `spsc_channel.hpp:27-60` moves the payload before releasing the consumer sequence. Java `EventFactory` prepopulates mutable objects; identical allocation and reuse behavior is **not established**. |
| Event publication through `EventTranslator` overloads ([sink][sink]) | **Absent** | **Not required** | C++ publishes a typed value through `try_publish(T)` / `try_push(T)` (`disruptor.hpp:327-344`; `spsc_channel.hpp:27-29`). Translators are optional general-library ergonomics, not currently used by Matching. |
| Atomically claim/publish a range of `n` producer sequences; `publishEvents`/`tryPublishEvents` ([sequenced][sequenced], [sink][sink]) | **Absent** | **No** | `SingleProducerSequencer::try_claim` and `MultiProducerSequencer::try_claim` each claim one slot (`disruptor.hpp:162-184,228-250`). A bounded consumer batch (`disruptor.hpp:543-591`) is **not** a producer batch claim. Possible future throughput optimization; evaluate against measured need. |
| `SequenceBarrier`, dependent consumers, fan-out ([guide][guide]) | **Implemented** | **No** | `disruptor.hpp:448-526`; `disruptor_sequencing_test.cpp:318-343,415-443`. Direct production channels have one terminal consumer. A staged one-ring topology remains an experiment (`disruptor_sequencing_test.cpp:712-765`). |
| `BatchEventProcessor` with a maximum batch size ([change][change]) | **Partial** | **No** | C++ `poll(maximum_batch)`/`run(..., maximum_batch)` (`disruptor.hpp:528-637`) handle bounded batches and failures. They do not implement Java's complete `EventHandler` contract, batch-start callback, queue-depth callback, or processor restart semantics ([handler][handler], [processor][processor]). |
| Busy-spin and blocking wait strategies ([wait][wait]) | **Partial** | **No generic strategy selected in production** | C++ has busy-spin, yield and blocking (`disruptor.hpp:84-112,417,479-503`). It lacks Java's full set of configurable wait strategies, timeout signals, and phased backoff. Production supervisor has its own wait policy (`matching_runtime_supervisor.cpp:410-421`). |
| Event handler lifecycle, exception policy and batch rewind ([change][change], [handler][handler]) | **Partial / rewind absent** | **No** | `PipelineFailure` and processor catches fail closed (`disruptor.hpp:419-446,543-591,620-628`). Java supports `onStart`, `onShutdown`, timeout callbacks, exception handlers and `RewindableException`/`BatchRewindStrategy`. Rewinding already-applied Matching commands is not safe without a proved rollback/idempotency protocol. |
| DSL: `Disruptor`, `EventHandlerGroup`, `after(...)`, thread factory, start/halt/draining shutdown ([dsl][dsl]) | **Absent** | **Not required** | Dependency wires and thread owners are explicit in C++ tests (`disruptor_sequencing_test.cpp:318-343`). Production owns lifecycle separately (`matching_runtime_supervisor.cpp:149-177,244-280,292-385`). A complete Java-compatible graph builder and lifecycle manager are not present. |
| `EventPoller`, dynamic gating-sequence registration and general consumer-access abstractions ([api][api], [ring][ring]) | **Absent / static alternative** | **No** | C++ accepts gating references on construction (`disruptor.hpp:59-82,324-325`). `DisruptorSpscChannel::try_pop` is a specific queue interface (`spsc_channel.hpp:31-43`), not the Java `EventPoller` contract. Adding/removing gates while active is unsupported. |
| `WorkerPool` / `WorkProcessor` / `handleEventsWithWorkerPool` ([change][change]) | **Correctly absent** | **No** | **Removed by Java 4.0.0 itself.** Do not count these legacy Java 3.x APIs as parity gaps or implementation requirements. |
| Kafka delivery acknowledgment, retry, replay and offset commit | **Outside the Java Disruptor API** | **Yes, in application components** | Matching output is framed as `RuntimeEventOutput` plus `RuntimeEndOfInput` (`matching_runtime.hpp:25-37`; `matching_runtime.cpp:62-94`). Kafka completion queue uses `DisruptorSpscChannel` (`rdkafka_runtime_adapter.cpp:342-350,372-383,537-553`). Delivery ACK and commit belong to `partition_replay_coordinator.cpp:563-649` and `matching_partition_runtime_driver.cpp:227-263`. A ring consumer cursor is **not** a broker ACK or committed offset. |

## What remains unverified

Tests establish **bounded examples**, not feature parity. Generic coverage
includes SPSC wrap and move-before-reuse (`disruptor_sequencing_test.cpp:25-84`),
two-ring 0/1/N frame interleaving (`:85-194`), multi-producer sequencing
(`:243-315`), dependent consumers (`:318-343`), handler failure
(`:444-589`), and one-ring feasibility (`:712-837`). These examples do not
establish Java API equivalence, production-wide ThreadSanitizer coverage,
long-running stop/restart equivalence, or latency/throughput parity.
The production path uses two channels (`matching_runtime.hpp:79-85`).
It reserves capacity for the maximum Core event burst and end marker before
mutating the book (`matching_runtime.cpp:62-94`).

## Acceptance priorities

1. **Gate A — preserve application correctness.** Verify the two-ring producer
   and consumer ownership, publication acquire/release, slow-consumer gating,
   reclamation, whole-frame admission, 0/1/N output and end marker, Lease
   failure closure, replay identity, Kafka ACK-before-offset-commit, callback
   overflow, and shutdown with pending outputs. Require production integration
   tests with actual adapters and source-aligned native sanitizer evidence.
2. **Gate B — prove operational fitness.** Run bounded stress and supported
   ThreadSanitizer checks on the actual two-channel runtime. Compare memory,
   allocations, p50/p99 latency and sustained throughput with the former
   implementation under the same configurations. Record results before
   making performance claims.
3. **Gate C — extend the reusable library only when demanded.** Evaluate
   producer batch claims, EventTranslator-style convenience, configurable
   wait policies, lifecycle callbacks, and explicit graph construction as
   separate requirements. Record a use case and testable contract for each.
   Do not add WorkerPool. Do not add batch rewind to Matching without a safe
   state-recovery design.

**Conclusion:** The two-ring migration can meet SimpleMatch's specified
behavior without full Java 4.0.0 parity. Several generic Java facilities are
indeed unimplemented. Treat them as optional library scope unless a concrete
production requirement depends on one. Evidence for complete behavioral and
performance parity remains outstanding.

[change]: https://lmax-exchange.github.io/disruptor/changelog.html
[guide]: https://lmax-exchange.github.io/disruptor/user-guide/
[api]: https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/package-summary.html
[ring]: https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/RingBuffer.html
[sink]: https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/EventSink.html
[sequenced]: https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/Sequenced.html
[handler]: https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/EventHandler.html
[processor]: https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/BatchEventProcessor.html
[wait]: https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/WaitStrategy.html
[dsl]: https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/dsl/Disruptor.html

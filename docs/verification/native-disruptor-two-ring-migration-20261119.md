# Native two-ring Disruptor transport migration: 2026-11-19

## Decision and source scope

Base revision: `bc508817` on `master`. Retain two independent Matching
data directions. Use the C++20 Disruptor `SingleProducerSequencer` and
`RingBuffer` for both directions. Do not merge command and event storage.

`MatchingRuntime` now owns one `DisruptorSpscChannel` for commands and one
for output events. The librdkafka delivery callback uses a third channel
for publication completions. This third channel is not a Matching data ring.
Each channel owns its terminal consumer gate. Publication uses release
ordering. The consumer acquires publication, moves the value, clears the
slot, and then releases the consumed sequence.

The migration does not change `CoreCommand`, `RuntimeOutput`, output indices,
`RuntimeEndOfInput`, Kafka record bytes, partition ownership, or offset
completion rules. `MatchingRuntime::process_one()` still reserves capacity
for the maximum event burst and the end marker before it consumes a command.
The caller receives `kOutputBackpressured` without Core mutation when a
complete frame cannot fit.

## Legacy removal inventory

| Removed item | Reason |
| --- | --- |
| `matching-engine/include/simplematch/matching/runtime/bounded_spsc_ring.hpp` | All production callers use Disruptor |
| `BoundedSpscRing<T>` | Replaced in both Matching directions and the Kafka completion queue |
| `SpscSequence` and `kRuntimeCacheLineSize` | Only the removed ring used these names |
| `MatchingRuntime::output_ring()` | Its only external consumers were test-only synthetic-output injection |
| Four `BoundedSpscRingTest` cases | The old implementation no longer exists; new public Disruptor tests own the required behavior |

An exact source search over `matching-engine` and `CMakeLists.txt` found no
remaining legacy class, header, or test references. Historical ADRs and
verification reports retain their original evidence and source names.

## Behavior and failure boundaries

`DisruptorSpscChannel` preserves fixed power-of-two capacity, FIFO order,
no overwrite before terminal consumer release, and nonblocking operations.
The generic Disruptor also supports barriers, batch processors, and
multiple consumer dependency graphs. The production layout still uses one
producer and one terminal consumer per ring. Do not add concurrent calls
to a single producer without changing the enclosing coordinator and
sequence-assignment ownership contract.

The Kafka completion callback cannot throw across librdkafka's C callback
boundary. If the completion channel rejects an event, the callback records
the first lost context. The publisher reports its publication ID as fatal
once. It retains the context and cannot report a successful ACK for the
lost completion. Future async submissions fail closed. The normal driver
serializes librdkafka polling, completion consumption, and publishing on
one owner thread. Other concurrent poll/flush callers are not supported.

The Kafka offset coordinator still records durable ACKs independently of
in-memory ring cursor movement. Replay, Lease fencing, and the sequence of
Kafka commits remain under the existing owner modules.

## Local verification and limits

The source-aligned validation consists of the full Native `full-native-dev`
CTest suite, `ci-sanitize` ASan/UBSan CTest, focused public-interface tests,
and the offline Gradle manifest/static-analysis checks. A bounded two-ring
GCC ThreadSanitizer smoke exercises separate ingress, Core and publisher
threads. It validates up to two business events and one end marker per
command, producer preflight, wraparound, and non-overwrite behavior without
a broker. It is not a
complete TSan test of the entire Matching stack.

| Verification | Source-aligned local result |
| --- | --- |
| `DisruptorSequencingTest.*` | 23/23 PASS |
| `ctest --preset full-native-dev --output-on-failure` | 129/129 PASS |
| `ctest --preset ci-sanitize --output-on-failure` | 112/112 PASS (ASan/UBSan) |
| Two SPSC channel tests repeated 200 times | PASS |
| GCC `-fsanitize=thread` standalone two-ring smoke | PASS; 1,200 input commands, 0/1/2 outputs plus end marker |
| `./gradlew manifestCheck staticAnalysis --offline --console=plain --max-workers=2` | BUILD SUCCESSFUL; 122 tasks |
| `clang-format --style=file --dry-run --Werror` on new/changed Disruptor C++ files | PASS |
| `git diff --check` and exact legacy-symbol search | PASS; zero remaining source references |

The new `take_for_consumer()` had an initial compilation RED test. Its
implementation moves the payload out of its slot and clears that slot
before advancing the gate. A separate failure-injection test verifies
that an upstream handler failure cannot acknowledge its partially written
event to a dependent consumer.

Tests also cover a production-facing backpressure retry with a retained
input and a zero-event `RuntimeEndOfInput`. Generic Disruptor tests cover
producer gating, multi-producer publication order, publication failure,
slot lifecycle, dependent barriers and multi-event framing.

The offline ThreadSanitizer smoke used a temporary executable. It was
removed after execution. No broker-connected callback overflow test ran.
This local migration does not certify Kubernetes deployment, Hosted CI,
full `verifyAll`, performance parity, or a 4 GiB Pod memory envelope.
Before claiming complete-local acceptance, follow the certification
runbook with a new exact-owned test run and safe cleanup. Keep the existing
two-ring production configuration until such evidence is collected.

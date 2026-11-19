# Native Disruptor single-ring feasibility: 2026-11-19

## Scope and decision

The local source baseline was `9b88b64f` on `master`. This slice evaluates
one ring with a Matching stage and a dependent Publishing stage. It does not
replace the two production SPSC rings, change any Kafka adapter, or change
event bytes. ADR 0011 records the design and its adoption limits.

The single-ring dependency graph is viable with bounded, slot-owned output
storage. It is **not approved for production adoption**. The existing double
SPSC transport remains in use until variable output-burst memory, publisher
retry, ownership, replay, Lease fencing, and Kafka ACK contracts are proven.

## Public-interface tests

`OneRingMatchingPipelineGatesOnSlowPublisher` exercises a two-slot ring.
The ingress producer publishes decoded Core commands. Matching copies each
CoreEvent into the published slot and appends `RuntimeEndOfInput`. Publishing
depends on Matching's release-published sequence. The terminal Publishing
sequence gates producer reuse. The test verifies that matching progress alone
does not make a full ring reusable.

The same test checks three concrete output shapes:

- Two resting Buy orders each produce one event and one end marker.
- One crossing IOC Sell produces two trades, one cancellation, and an end
  marker with `output_count=3`.
- A Close Barrier with no remaining resting orders produces only an end
  marker with `output_count=0`.

The test checks the Core's worst-case event capacity against the small fixed
slot array. It also bounds every event append and shares `PipelineFailure`
across both dependent stages. Each CoreEvent is copied before the next Core
command clears its event storage. The Publisher writes test output to a local
vector, not a Kafka producer or a durable publication ledger.

`FailedSingleRingMutationNeverReachesDependentPublisher` injects a handler
failure after an in-place slot mutation. The shared failure halts the
dependent publisher. Neither consumer advances its completion sequence, and
no partially written output becomes visible to the publisher handler.

`ConcurrentSingleRingStagesPublishOnlyCompletedResults` uses two real
`std::jthread` processors, 200 commands, and a four-slot ring. It checks
producer publication, stage transformation, dependent consumer visibility,
slow-consumer gating, stop, and bounded completion. An additional standalone
GCC ThreadSanitizer smoke used 400 commands and the same single-ring staged
release/acquire design. It completed without a reported data race. The
temporary smoke executable was removed after execution.

## Local verification

| Check | Result |
| --- | --- |
| `DisruptorSequencingTest.*` | 19/19 PASS |
| `ctest --preset full-native-dev --output-on-failure` | 129/129 PASS |
| `ctest --preset ci-sanitize --output-on-failure` | 112/112 PASS (ASan/UBSan) |
| Two single-ring tests, 100 consecutive repetitions | PASS |
| GCC ThreadSanitizer standalone two-stage smoke | PASS; exit 0, 400 commands |
| `./gradlew manifestCheck staticAnalysis --offline --console=plain --max-workers=2` | BUILD SUCCESSFUL |
| `clang-format --style=file --dry-run --Werror` on both C++ files | PASS |

The single-ring test's first build failed because `at_for_stage()` did not
exist. The added accessor resolved that compiler failure. A subsequent test
failure revealed an undersized book fixture, not a Disruptor publication
failure. The fixture now permits the expected crossing and cancellation.

## Production limitations

The high template permits at most `150 * 2 * 4096 + 1 = 1,228,801`
CoreEvents per command, plus one end marker. A fixed array of this size in
every command slot would multiply the large output reserve by ring capacity.
The local GCC C++20 type-size check found `sizeof(RuntimeOutput)=272` and
`sizeof(std::optional<RuntimeOutput>)=280` bytes. An inline worst-case
output array therefore consumes approximately 82 MiB **per input slot** for
the 1,024-order local template, or 328 MiB **per input slot** for the
4,096-order high template. This calculation is not a resident-memory or
deployment measurement. The temporary sizing executable was removed.
The current Core event reserve alone represents about 291 MiB of virtual
storage for the high template, according to the Issue 217 measurements.
Storing a `span` into `Core::events()` does not solve ownership because the
next Core command clears that storage.

`RingBuffer::at_for_stage()` relies on the caller to prove publication,
stage ownership, an acquired dependency sequence, and a live terminal gate.
It does not enforce those conditions. The thread sanitizer smoke covers only
its bounded fixture. It is not a full-source TSan certification.

The Publishing processor's terminal sequence means only that its handler
completed. It is not proof of Kafka persistence. The production Coordinator
must retain publication records and commit offsets only after all outputs
receive delivery ACKs. The implementation still lacks a production one-ring
batch-store design, Kafka replay and fencing validation, and comparative
latency and memory measurements. No Kubernetes or Hosted CI was run.

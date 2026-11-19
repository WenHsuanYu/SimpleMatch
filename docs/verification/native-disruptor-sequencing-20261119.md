# Native Disruptor sequencing: local verification on 2026-11-19

## Scope

This result covers the additive C++20 sequencing header, its regression
tests, and the design limits in ADR 0011. The base revision was `46ed1115`.
The new code does not replace `MatchingRuntime` or its two production SPSC
rings. All checks ran locally. No Kubernetes deployment, Hosted CI, GitHub
push, or high-capacity certification ran as part of this slice.

## Tests and evidence

| Verification | Result |
| --- | --- |
| `DisruptorSequencingTest.*` | 16/16 PASS |
| `ctest --preset full-native-dev --output-on-failure` | 126/126 PASS |
| `ctest --preset ci-sanitize --output-on-failure` | 109/109 PASS (ASan/UBSan) |
| `./gradlew manifestCheck staticAnalysis --offline --console=plain --max-workers=2` | BUILD SUCCESSFUL |
| `clang-format --style=file --dry-run --Werror` on both C++ files | PASS |
| `git diff --cached --check` | PASS |

The new two-direction test initially failed to compile because it used the
private `MatchingRuntime::RuntimeInput`. A test-local input frame fixed that
error without changing the production interface. The new shared-failure
test first observed an incorrect consumer acknowledgement. A post-handler
barrier check fixed that regression. A capacity test first failed near
`INT64_MAX`: the preflight reported four writable slots when only one or
two sequence IDs remained. The fix bounds free capacity by both the slowest
consumer and the remaining absolute sequence IDs.

Three selected tests passed 200 consecutive repetitions: multi-producer
publication, fan-out gating, and shared-failure acknowledgement. A separate
GCC ThreadSanitizer smoke executable tested four producers with 200 events
each, across a 16-slot ring. It exited successfully with no reported race.
The temporary executable was removed after the run. This is not a full
ThreadSanitizer build of the repository or proof that all races are absent.

## Remaining integration gates

The new ring example checks worst-case output headroom before Core mutation.
It fails closed when it cannot admit a complete output frame. Unlike the
existing runtime, it does not retry normal output backpressure.

The production migration must retain one producer per SPSC ring, input
and output framing, deterministic event bytes, Kafka replay and ownership
fencing. It must also preserve output publication ACK before Kafka offset
commit. Consumer access to the new `at()` and `has_event()` methods still
requires a live gating sequence and prior publication acquire. Shared
failure propagation depends on wiring the same `PipelineFailure` into all
dependent processors. Failure and acknowledgement are not globally atomic.

No complete LMAX parity, production deployment, full ThreadSanitizer suite,
or 4 GiB Pod memory claim follows from these local tests.

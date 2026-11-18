# Native verification through Gradle

Run `./gradlew nativeTest` from the repository root.
The task configures and builds `dev-debug`, then executes the existing CTest suite.
Install the normal CMake, Ninja, compiler, and vcpkg prerequisites first.
Set `VCPKG_ROOT` to the existing vcpkg installation.
This task does not install host tools or start Docker resources.

CMake and CTest remain the native build and assertion owners.
Gradle calls them directly without a Shell launcher or a Java copy of their tests.
Normal dependency and compilation caches remain available.
CTest executes on every invocation and rejects empty, failed, skipped, or disabled results.

| Property | Meaning |
| --- | --- |
| `nativePreset` | `dev-debug` by default. Also accepts `full-native-dev`, `ci-fast`, and `ci-sanitize`. |
| `nativeParallelism` | Build and test parallelism from 1 through 8. The default is 2. |
| `nativeEvidence` | New evidence directory. The default is `out/verification/native/<UUID>`. Existing paths fail before configuration. |

For the messaging-enabled native suite, use:

```bash
./gradlew nativeTest -PnativePreset=full-native-dev -PnativeParallelism=2
```

Each run writes `ctest.xml` with named test results in its new evidence directory.
Configure, build, and test commands have separate bounded process deadlines.
Their failures propagate through Gradle.
An interrupted invocation provides no PASS and requires a new evidence path.

The existing CTest capacity benchmark checks actual native command/event integrity and deterministic replay.
The runtime-driver suite also includes bounded 10,000-command local-day replay.
S5 retires the separate ten-cycle Shell loop and its aggregate schema.
See the [disposition](../plans/shell-test-reduction-s5.md) for the intentional verification loss.
Native results do not prove Kafka delivery, Kubernetes recovery, a wall-clock soak, or production capacity.

## Optional 150-book saturation check

The regular CTest capacity benchmark immediately cancels each resting order. It verifies
command processing and replay, but it does not fill both sides of every order book.

Use this separate target to check the complete local Matching Core envelope:

```bash
cmake --preset full-native-dev
cmake --build --preset full-native-dev --target simplematch_matching_capacity_envelope_test --parallel 2
out/build/full-native-dev/simplematch_matching_capacity_envelope_test --gtest_filter='MatchingCapacityEnvelopeTest.*'
```

The executable is excluded from CMake `ALL` and from CTest. It does not run during `nativeTest` or
`verifyAll`. Its 150-book scenario accepts one Core Open Barrier and 307,200 non-crossing orders.
Each book holds 1,024 buy orders and 1,024 sell orders. The test rejects 300 additional orders,
one per side per book, without changing order counts. It requires zero unintended trades.

Two separate Core instances process the same deterministic command sequence. The test compares
accepted-command semantics, rolling digests of serialized event keys and values, and final state
checksums. It also rejects duplicate output keys. It keeps the event keys but not the serialized
value payloads. A Linux run reports the test process's peak RSS. That value is **not** the
Matching Pod's memory use.

This is a deterministic Core and event-encoding test, not a deployed-service test. An
over-capacity Core rejection causes the deployed coordinator to fail closed; the test does not
prove non-disruptive live refusal.

## Optional high-capacity Core workload

The same opt-in executable has a smaller high-capacity sequence. It fills one instrument with
4,096 orders on each side, cancels all 8,192 orders, and fills both sides again. It requires
24,577 accepted Core commands including Open Barrier. It checks the per-phase order count,
event semantics, serialized key/value digests, and final deterministic Core state. Run:

```bash
out/build/full-native-dev/simplematch_matching_capacity_envelope_test \
  --gtest_filter='MatchingCapacityEnvelopeTest.RefillsOneBookAfter4096PerSideCancellation'
```

The 150-instrument high-capacity sequence is `DISABLED_StagedHigh150Books4096PerSide`.
It requires both an exact GoogleTest filter with `--gtest_also_run_disabled_tests` and the
`SIMPLEMATCH_HIGH_ENVELOPE_APPROVAL=RUN_3686400_CORE_COMMANDS` environment value. It will not
run from ordinary CTest or the default opt-in test filter. Before running this expensive test,
check current CPU and memory headroom and apply external resource and elapsed-time bounds.

The high-capacity Core-only run is supplementary to the required local fill-once acceptance;
the staging/production template contract is checked through manifests and capacity arithmetic.

## Optional constructor and fingerprint memory profiles

The following target is separate from default CMake `ALL` and CTest. The default invocation
prints source-backed object sizes and samples a small Runtime and an 8,192-node map proxy:

```bash
cmake --build --preset full-native-dev --target simplematch_matching_memory_envelope_test --parallel 2
out/build/full-native-dev/simplematch_matching_memory_envelope_test \
  --gtest_filter='MatchingMemoryEnvelopeTest.*'
```

The high-capacity constructor profile needs `SIMPLEMATCH_RUN_MEMORY_ENVELOPE_FULL=1` and an
exact GoogleTest filter. It applies a 2 GiB process virtual-memory limit on Linux before
allocating its 150-book Core, 2,097,152-slot Runtime Ring, and empty Coordinator.

The 4,000,000-node fingerprint *proxy* needs the exact
`SIMPLEMATCH_MEMORY_PROXY_APPROVAL=RUN_4000000_PROXY_NODES` environment value, an exact filter,
and `--gtest_also_run_disabled_tests`. It also applies a 2 GiB process virtual-memory limit.
The proxy has the same key/value shape as the private Coordinator map, but it is not the actual
Kafka command ingestion path. Both profiles should run under an external elapsed-time bound.

The independent memory measurements and their resource bounds are documented in
[`matching-capacity-issue-217.md`](matching-capacity-issue-217.md). These profiles are
optional diagnostic tools, not additional staging/production acceptance workloads.

# Matching capacity evidence — Issue #217

This report records offline Native measurements made on 2026-11-19. The tests extend the
working tree based on `2a711ac5`. They use `full-native-dev` (Debug) with GCC 15.2.0 and
CMake 4.4.4 on an AMD Ryzen 7 5800X host. This is not Hosted CI or deployed Kubernetes
evidence. The workloads do not start Kafka or alter the canonical kind cluster.

Source SHA-256 at the time of the bounded runs (both test sources are unchanged by the
subsequent documentation-only updates):

- `matching_capacity_envelope_test.cpp`: `45125333551604c0482dd18dfc2681f2724bcd67f60845f3342f41cb8c72e80a`
- `matching_memory_envelope_test.cpp`: `a336c094ccaaa885d349ef6961d4b57503ac82ec71632a91ded24deb7140e1b2`

## Capacity contract

| Profile | Books | Resting orders per side | Simultaneous orders | Accepted commands needed |
| --- | ---: | ---: | ---: | ---: |
| Local, one fill | 150 | 1,024 | 307,200 | 307,201 incl. Open Barrier |
| Staging/production, fill-cancel-refill | 150 | 4,096 | 1,228,800 | 3,686,401 incl. Open Barrier |

The accepted command counts include a successful Open Barrier and use distinct command IDs;
they are cumulative, not concurrent resting orders. The local test also attempts 300 extra
orders with distinct command IDs that the Core rejects: 307,501 Core calls including the
barrier, of which 307,201 are accepted. These probes do not exercise Coordinator admission.
The high-capacity template specifies a 4,000,000 distinct-command ceiling. This is a
configuration value, not the number of commands processed in the Core-only run.

## Matching Core workload results

| Workload | Result | Commands incl. barrier | Runtime | Peak process RSS |
| --- | --- | ---: | ---: | ---: |
| Local 150-book fill, two sequential runs | PASS | 307,201 per run | About 14.3 s combined | About 70.5 MiB |
| High 1-book fill-cancel-refill, two runs | PASS | 24,577 per run | About 1.3 s combined | Included above |
| High 150-book fill-cancel-refill, one run | PASS | 3,686,401 | 104.76 s | About 98.9 MiB |

The local case rests 307,200 non-crossing orders. It then rejects 300 extra orders in the
Core without modifying the corresponding books. No unintended trade occurs. Two sequential
replays have matching command/event semantic digests, serialized event-key/value digests,
and final Core state checksums. Every serialized event key is unique in the local case.
The full default opt-in test process, including both local runs and both one-book high
sequences, took about 15.8 seconds.

The high 150-book test applies 1,228,800 valid additions, 1,228,800 valid cancellations,
and 1,228,800 valid additions. It requires one event for each command, no trades, zero
resting orders after the cancellation phase, and 1,228,800 resting orders after refill.
The three Core state checksums are:

| Phase | Deterministic state checksum |
| --- | ---: |
| Initial fill | `5230413515152401556` |
| All canceled | `16743504601157425312` |
| Refill | `2537019124709926435` |

The high sequence's serialized key/value digest is `6406040077184160469`. The test
compares each event with the expected semantic fields. A rolling digest is not a stored
byte-for-byte comparison of all 3,686,400 records. The measured times are Debug Core
wall time on this host, not production transactions per second or latency commitments.

The full high test requires an exact environment acknowledgement and a disabled GTest
filter. For this run, the process had a 2 GiB virtual-memory bound, a 240-second
elapsed-time bound, and a lower CPU scheduling priority. It exited successfully.
The ceiling is **external to the GTest binary**, not enforced by that Core test itself.
After confirming host resource headroom, the explicitly opt-in, bounded invocation is:

```bash
nice -n 10 /usr/bin/time -f 'wall_s=%e peak_rss_kib=%M exit=%x' \
  prlimit --as=2147483648 -- timeout 240s \
  env SIMPLEMATCH_HIGH_ENVELOPE_APPROVAL=RUN_3686400_CORE_COMMANDS \
  out/build/full-native-dev/simplematch_matching_capacity_envelope_test \
  --gtest_filter='MatchingCapacityEnvelopeTest.DISABLED_StagedHigh150Books4096PerSide' \
  --gtest_also_run_disabled_tests --gtest_brief=1
```

The original high run log was `/tmp/simplematch-high-full-150-result.log` (SHA-256
`fc61b5a895ab5a6552bd0c2b374bda7ce1d387af0070461f9c15dcfb08df28bb`).
This temporary file is not committed; the checksums and result are recorded above.

## Memory measurements

`simplematch_matching_memory_envelope_test` is opt-in and excluded from CMake `ALL` and
default CTest. It measures a real 150-book high-capacity Core and Runtime constructor,
then independently measures an equivalent key/value `std::map` proxy. The proxy is not
the Coordinator's private container and does not perform command ingestion.

| Measurement | Resident memory or incremental resident memory |
| --- | ---: |
| 150-book high Core constructed, before ring | About 12.8 MiB process RSS |
| High Runtime with 2,097,152 output slots | About 573.2 MiB process RSS |
| High Coordinator constructed, empty fingerprint history | About 573.3 MiB process RSS |
| Map proxy after 500,000 nodes | +61.1 MiB over proxy baseline |
| Map proxy after 4,000,000 nodes | +488.4 MiB over proxy baseline |

The Output Ring's `std::optional<RuntimeOutput>` slot storage is 560 MiB by type size.
The reserved 1,228,801 CoreEvent slots represent about 291 MiB of virtual storage,
not necessarily resident pages until those events are written. The 4-million-entry map
proxy was measured in a separate process; independent process measurements are not
additive peak-RSS observations.

The high constructor and map-proxy tests ran with a 2 GiB virtual-memory bound.
The constructor test completed in about 0.6 seconds. The proxy populated four million
nodes in about 3.74 seconds. They are isolated instrumentation results rather than
an end-to-end workload.

The two manually opted-in memory runs used these bounded commands. The test source also
sets its own Linux `RLIMIT_AS` ceiling before allocation. These are **not** safe to
include in a default test target:

```bash
nice -n 10 prlimit --as=2147483648 -- timeout 90s \
  env SIMPLEMATCH_RUN_MEMORY_ENVELOPE_FULL=1 \
  out/build/full-native-dev/simplematch_matching_memory_envelope_test \
  --gtest_filter='MatchingMemoryEnvelopeTest.FullConstructorProfileRequiresExplicitOptInAndBound' \
  --gtest_brief=1

nice -n 10 prlimit --as=2147483648 -- timeout 150s \
  env SIMPLEMATCH_MEMORY_PROXY_APPROVAL=RUN_4000000_PROXY_NODES \
  out/build/full-native-dev/simplematch_matching_memory_envelope_test \
  --gtest_filter='MatchingMemoryEnvelopeTest.DISABLED_ProfilesFourMillionFingerprintMapProxyNodes' \
  --gtest_also_run_disabled_tests --gtest_brief=1
```

Their temporary raw run logs have SHA-256 values
`baf3422c11c374b7974ebc564994dad85435d8a2d8415407e281a88a2ba5a705` (constructor)
and `7cac486bc6f43803e13e44fe1e657fc29ec0a48e90af103bc23cd9da203bbf51` (proxy).

## Source-aligned regression results

After the current changes, `full-native-dev` built and ran CTest **105/105 PASS**;
`ci-sanitize` built and ran CTest **88/88 PASS**. The separate sanitized opt-in target
ran its normal capacity tests **5/5 PASS** (150-book high remains disabled), and the
memory target reported **3 PASS, 1 expected opt-in SKIP, 1 disabled**. Offline Gradle
`manifestCheck staticAnalysis` passed (122 actionable tasks, 1 executed). These checks
cover the current Native and manifest verification scope without a staging/production deployment.

## Acceptance scope

Issue #217 requires the executable local 150-book fill-once capacity test and checks the
shared staging/production overlays as static configuration contracts. The full high Core-only
sequence and memory profiling are additional completed measurements, not required acceptance
tests. The existing kind cluster and registry were not modified.

The optional tests can be reproduced through the commands in
[`native-verification.md`](native-verification.md). The Issue #217 acceptance checklist
contains the authoritative scope.

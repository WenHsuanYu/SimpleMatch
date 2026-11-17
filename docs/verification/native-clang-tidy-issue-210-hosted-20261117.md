# Native clang-tidy #210 — source-pinned Hosted warning triage

Status: **PARTIAL / review queue**, not an accepted clean baseline or a resolved issue.
Evidence date: 2026-11-17 (Asia/Taipei).

## Authoritative input and reproducibility

- [GitHub Actions run #44050593821](https://github.com/WenHsuanYu/SimpleMatch/actions/runs/44050593821), attempt 1, `completed/success`; [job #133927123553](https://github.com/WenHsuanYu/SimpleMatch/actions/runs/44050593821/job/133927123553).
- Analyzed and source-reviewed commit: `2b7eeaea2bf1a204d5043fd72428c2d9526f6e40`; source tree `d1f329e2423269cae6c245ad8b2f3c19e6cf9ed1`.
- Exact Hosted toolchain: LLVM clang-tidy **21.1.6**, SHA-pinned matching `run-clang-tidy.py`, seven requested check families, **332** selected check names. The single approved Google exception remains `modernize-use-trailing-return-type`; `WarningsAsErrors: ''`.
- 28 handwritten `.cpp` translation units; three compiled generated protobuf `.pb.cc` units excluded from analysis; 16 owned `.hpp` files contribute to the 44-file source fingerprint.
- Owned source fingerprint (SHA-256, as computed by the reporter): `7ea98e0c6d0f0909ed87af9990dedaee4ff94c6c59c4b72aed27c8db6cdaa401`.
- Policy SHA-256: `c254e0c6eb0da453cd86190d5a548cac441def018fd069541b12dd57eb79ce05`.
- Original Hosted artifact `native-clang-tidy-advisory-44050593821-1`, ID `14315220143`, ZIP SHA-256 `0b84555bd27db7a0da8ded43f55ad96ecfe2940518a333fb72b78635ee09d183`. Artifact report SHA-256 `5de2c847279b9a2f1de53159fa5a2c5ed81629e620e7b9908cff6c669430514e`. Artifact expires 2026-12-01 (UTC).
- Independent log normalization: **2,998 emitted warnings**, **1,048** unique `(path,line,column,check_tag)` sites and **956** unique `(path,line,check_tag)` sites in **40** owned files, **43** bracketed check tags. The 1,048 and 956 measurements use different uniqueness keys. Neither represents confirmed bug counts.

The companion [complete 956-row ledger](native-clang-tidy-issue-210-hosted-20261117-sites.csv) was regenerated from **the actual Hosted job log**, not from the historical LLVM 21.1.8 985-site CSV. Every row contains its original owned path, line, all warning columns, complete check tag, number of emitted occurrences, and immutable source **Git blob SHA-1** from that commit's tree. The blob SHA-1 field is *not* a claim of SHA-256. It additionally contains an auto-assigned risk lane and separate, explicit manual review status. All 956 exact `(path,line,tag,count)` tuples were independently rechecked against the Hosted log after publication on this evidence branch.

The CSV intentionally **excludes raw diagnostic messages, source excerpts, credentials, local paths and runtime payloads**. Its rule-based lane is only an inspection queue, never a true-positive/false-positive determination. The full log remains GitHub-run evidence and is not copied into the repository.

## Verified triage coverage — not a false clean claim

| Site review state | Unique line/tag sites | Meaning |
| --- | ---: | --- |
| `UNADJUDICATED` | **921** | Auto-categorized for scheduling only; **no source-specific correctness or false-positive determination** |
| `REVIEWED_CONTEXT_UNRESOLVED` | **31** | Source around the exact location examined and a contextual note recorded; true/false status still undetermined |
| `CONFIRMED_STYLE_FINDING_UNFIXED` | **4** | Manually confirmed real syntactic unnamed-parameter findings; not correctness bugs; still unfixed |
| **Total** | **956** | Full current-source inventory; only **35** have any manual source context |

Auto-triage *lanes*, all unresolved unless explicitly reviewed, sum to 956: ABI/protocol/buffer (291), manual design/style (286), include dependencies (274), performance/layout (50), initialization/correctness (17), ownership/lifetime (17), test allocation interceptors (17), and callback/override naming (4). These counts are **not severity rankings**.

## Source-specific inspection completed

The following source-context notes were verified against the immutable commit above; exact corresponding ledger rows have review notes and statuses. These are **not blanket classifications of a check family**.

1. **Four true style-only unnamed-parameter findings:** `matching_main.cpp:41` is a POSIX signal-handler argument; `rdkafka_runtime_adapter.cpp:366` and `matching_kafka_fixture_publisher.cpp:170` are librdkafka callback ABI arguments; `matching_partition_runtime_driver_test.cpp:190` is a deliberately ignored fake-override record. Fixes, if desired, must preserve signatures and type identity. No correctness bug was demonstrated.
2. **Seven `cppcoreguidelines-pro-type-member-init` sites:** `deterministic_matching_core.hpp:110,152`, `matching_command_decoder.hpp:28`, `matching_runtime.hpp:25,75`, and `partition_replay_coordinator.hpp:59,109`. Inspected producers include command factories, decoder aggregate returns, runtime output/input aggregate construction and JSON baseline loading. The sampled paths populate their values, but that does **not** establish that every construction/read path is safe. Remain unresolved pending bounded use-site proof and negative tests if a bug is confirmed.
3. **Seventeen `cppcoreguidelines-owning-memory` / `cppcoreguidelines-no-malloc` line/tag sites:** `matching_core_allocation_test.cpp:36,53,66,144,148,152,156,188,193` (overlapping checks). They occur in intentional test-only global `new/delete` interception and allocation calibration; they are **not evidence of an allocation in the Matching Core hot path**. Any cleanup must preserve the full allocation operator ABI and positive calibration. Their policy disposition still requires explicit decision.
4. **Two reinterpret casts:** `matching_event_encoder.cpp:110` converts SHA-256 bytes to a string's byte representation; `partition_replay_coordinator.cpp:72` passes `string_view` storage to the OpenSSL SHA256 C API. These are byte/ABI-boundary reviews, not automatic removal candidates. A change needs byte-identity and alignment/aliasing tests.
5. **One forwarding warning:** `bounded_spsc_ring.hpp:74` invokes the named callback repeatedly; unconditional perfect-forwarding inside that loop could change callable state/lifetime semantics. No auto fix.
6. **Four bounded-pointer sites:** `matching_event_encoder.cpp:69,77` guard a fixed buffer before copy/write; `matching_runtime_supervisor.cpp:36,37` construct `std::from_chars` pointer boundaries from `string_view`. The first helper should additionally retain a proof that input lengths cannot overflow `size_ + value.size()`; no reachable bug has been established.

## Next review batches and decision gates

**Priority A — safety and correctness:** verify all seven member-init values from constructors/factories through consumers; study the 20 array-index and 18 pointer-arithmetic sites, 17 array-decay sites, C interop aliases, lifetime and special-member cases. Record one of: confirmed source bug with RED/GREEN regression; intentional, justified use with exact contract; or unresolved with stated missing proof. Avoid blanket suppressions.

**Priority B — performance/ABI:** inspect 20 enum-width sites, three padding sites, five unnecessary-value-param sites and related ownership/lifetime warnings. Before changes to layouts, public signatures, hot-path algorithms or Kafka/FIX wire representation, require byte/ABI and throughput/alloc evidence rather than applying suggested fix-its.

**Priority C — maintainability/style:** adjudicate 274 include-cleaner positions against actual include ownership; determine which magic-number and public-aggregate diagnostics express domain constants/DTO design rather than defects; apply only narrowly validated mechanical changes.

**Issue boundary:** [#210](https://github.com/WenHsuanYu/SimpleMatch/issues/210) must remain **OPEN** with its source-specific adjudication criterion unchecked. The initial 956-row ledger and 35 inspected sites do not satisfy full per-site true/false-positive triage. Parent [#209](https://github.com/WenHsuanYu/SimpleMatch/issues/209) AC-09 remains partial, and [#214](https://github.com/WenHsuanYu/SimpleMatch/issues/214) AC5 (mandatory subset and fixtures) remains blocked. No `-fix`, blanket `NOLINT`, rule suppression, formatting sweep or blocking CI change was made.

## Publication / workspace limitation

This evidence is committed on **`evidence/issue-210-hosted-triage-20261117`**, branched from the exact analyzed HEAD; it is **not merged into master**. The Chat On Steroids local-workspace tunnel was unavailable at execution time, so there was no change to the user's working tree, local validation environment or running CI. The CSV was read back and reconciled by exact site identity and emitted count against the GitHub-hosted source log. The branch can be reviewed and brought into the canonical workspace when its connection is restored.

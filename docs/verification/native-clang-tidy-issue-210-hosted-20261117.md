# Native clang-tidy #210 — source-pinned Hosted warning triage

Status: **COMPLETE SOURCE-SITE REVIEW / PARTIAL TP-FP ADJUDICATION**; not lint-clean or an accepted blocking policy.
Evidence started: 2026-11-17; full source-site review: 2026-11-18 (Asia/Taipei).

## Authoritative input and reproducibility

- [GitHub Actions run #44050593821](https://github.com/WenHsuanYu/SimpleMatch/actions/runs/44050593821), attempt 1, `completed/success`; [job #133927123553](https://github.com/WenHsuanYu/SimpleMatch/actions/runs/44050593821/job/133927123553).
- Analyzed and source-reviewed commit: `2b7eeaea2bf1a204d5043fd72428c2d9526f6e40`; source tree `d1f329e2423269cae6c245ad8b2f3c19e6cf9ed1`.
- Exact Hosted toolchain: LLVM clang-tidy **21.1.6**, SHA-pinned matching `run-clang-tidy.py`, seven requested check families, **332** selected check names. The single approved Google exception remains `modernize-use-trailing-return-type`; `WarningsAsErrors: ''`.
- 28 handwritten `.cpp` translation units; three compiled generated protobuf `.pb.cc` units excluded from analysis; 16 owned `.hpp` files contribute to the 44-file source fingerprint.
- Owned source fingerprint (SHA-256, as computed by the reporter): `7ea98e0c6d0f0909ed87af9990dedaee4ff94c6c59c4b72aed27c8db6cdaa401`.
- Policy SHA-256: `c254e0c6eb0da453cd86190d5a548cac441def018fd069541b12dd57eb79ce05`.
- Original Hosted artifact `native-clang-tidy-advisory-44050593821-1`, ID `14315220143`, ZIP SHA-256 `0b84555bd27db7a0da8ded43f55ad96ecfe2940518a333fb72b78635ee09d183`. Artifact report SHA-256 `5de2c847279b9a2f1de53159fa5a2c5ed81629e620e7b9908cff6c669430514e`. Artifact expires 2026-12-01 (UTC).
- Independent log normalization: **2,998 emitted warnings**, **1,048** unique `(path,line,column,check_tag)` sites and **956** unique `(path,line,check_tag)` sites in **40** owned files, **43** bracketed check tags. The 1,048 and 956 measurements use different uniqueness keys. Neither represents confirmed bug counts.

The companion [complete 956-row ledger](native-clang-tidy-issue-210-hosted-20261117-sites.csv)
was generated from the **actual Hosted job log**, not the historical LLVM 21.1.8 985-site CSV.
Each row records path, line, warning columns, full check tag, emitted occurrences and immutable
source **Git blob SHA-1** from the analyzed commit. Git blob SHA-1 is not a SHA-256 checksum.
The CSV also separates the auto-assigned inspection lane from manual review status.

The final ledger was independently rechecked against the full Hosted log on `master`.
Two initial extraction errors required correction: diagnostic messages containing `[[nodiscard]]`
were incorrectly parsed as `check_tag=[nodiscard`, rather than the final
`modernize-use-nodiscard` check tag. The corrected locations are
`market_reference_artifact.hpp:21` (3 emissions) and
`matching_partition_runtime_driver_test.cpp:192` (1 emission). The other 954 source-site
identities and all 956 paths, lines, columns, source blobs and occurrence counts are unchanged.
Reparsing the 15,332-line log from the final bracketed check tag yields the same 2,998 warning
occurrences, 956 line/tag sites, 1,048 column-sensitive sites and 43 combined check tags.
This repairs **evidence extraction**, not C++ source or clang-tidy policy.

The CSV intentionally **excludes raw diagnostic messages, source excerpts, credentials, local paths and runtime payloads**. Its rule-based lane is only an inspection queue, never a true-positive/false-positive determination. The full log remains GitHub-run evidence and is not copied into the repository.

## Complete source-site coverage — conditional decisions remain open

**All 956 of 956 exact `(path,line,check_tag)` sites have a source-specific review note.**
This is 100% review *coverage*, not 100% resolved TP/FP decisions. Each site was checked
against the analyzed commit, declaration/caller/test context and the exact Hosted LLVM 21.1.6
warning text (recovered from the original 15,332-line job log). The sanitized, per-site warning
lookup was used for review only; it was not added to the repository. The immutable ledger still
records **2,998** emitted occurrences across 40 owned files and 43 combined check tags.

| Site review state | Unique sites | Meaning |
| --- | ---: | --- |
| `UNADJUDICATED` | **0** | No site remains without source-specific review |
| `REVIEWED_CONTEXT_UNRESOLVED` | **487** | Source context and exact warning checked; final TP/FP or proposed fix still needs proof |
| `REVIEWED_POTENTIAL_DEFECT_UNVERIFIED` | **8** | Localized correctness/configuration risk found; direct feature or deployment acceptance evidence is still missing |
| `CONFIRMED_STYLE_FINDING_UNFIXED` | **461** | Source confirms a syntactic/style diagnostic; no runtime bug implied; still unfixed |
| **Total** | **956** | **956 reviewed / 0 unreviewed** |

Only the 461 confirmed style rows set `true_positive=STYLE_ONLY_CONFIRMED` and
`false_positive=NO`; the 495 other sites retain `UNDETERMINED` for both TP and FP.
There is **no confirmed false-positive row**. A finding can be correct as a stylistic
observation and still be unsafe or unnecessary to rewrite. `REVIEWED_CONTEXT_UNRESOLVED`
means that an explicit outstanding precondition exists, **not** that a warning is harmless.

The rule-derived inspection lanes remain unchanged: ABI/protocol/buffer (291), design/style
(286), include dependencies (274), performance/layout (50), initialization/correctness (17),
ownership/lifetime (17), test allocation interceptors (17) and callback/override style (4).
Lanes are not severity rankings and never substitute for the per-site review state.

### Fully inspected high-volume check groups

| Exact check tag | Sites | Confirmed style | Context unresolved | Potential defect |
| --- | ---: | ---: | ---: | ---: |
| `misc-include-cleaner` | 274 | 155 | 119 | 0 |
| Combined magic-number tag | 210 | 5 | 200 | 5 |
| `misc-non-private-member-variables-in-classes` | 113 | 113 | 0 | 0 |
| `modernize-use-designated-initializers` | 91 | 91 | 0 | 0 |
| `misc-const-correctness` | 32 | 16 | 16 | 0 |
| `modernize-use-ranges` | 20 | 19 | 1 | 0 |
| Combined C-array avoidance tag | 17 | 17 | 0 | 0 |
| `modernize-use-nodiscard` | 2 | 2 | 0 | 0 |

The ledger has one note for every other check site too, including ownership/RAII, locking,
callback forwarding, memory/layout, raw C interfaces, and cognitive complexity. When a source
line has several warnings with the same combined tag, the row stays one identity and its
original `columns` and `occurrences` fields remain intact.

## Evidence-based source decisions

### Safety and correctness

- **Seven member-initialization sites:** Inspecting every current factory, aggregate return,
  ring publication and known consumer found no reachable read of an uninitialized scalar.
  `MatchingCommandContext`, `CoreCommand`, decoder result, runtime input/output, Kafka offset
  triples and baseline metadata are initialized on the traced paths. However, public aggregates
  can still be default-constructed outside these paths. The seven rows remain unresolved rather
  than being marked false positive. The ledger names exact creation/use lines and missing
  invalid/default-object tests.
- **40 pointer, index and reinterpret-cast sites:** Reviewed 20 constant-array-index sites,
  18 pointer-arithmetic sites and two `reinterpret_cast` sites. UUID/SHA hex nibble indices,
  bounded fixture `argv` uses, `from_chars` half-open ranges and immediate OpenSSL C byte views
  have concrete local bounds or API contracts. Preserve the exact binary digest and wire bytes.
  Three sites below need stronger negative evidence.
- **20 related C-interoperability sites:** Reviewed 17 array-to-pointer-decay and three tagged
  vararg sites. The libcurl/OpenSSL/librdkafka C boundaries must keep their buffer, header and
  ownership contracts. In particular, librdkafka error buffers use stack `char[512]` and the
  tagged `rd_kafka_producev` calls use `RD_KAFKA_MSG_F_COPY`; do not rewrite them mechanically.
- **17 allocation-instrumentation sites:** The `no-malloc` and `owning-memory` findings occur
  in the test executable's intentionally replaced global C++ `new/delete` operators and their
  malloc/free/aligned storage backend. The positive calibration test verifies that ordinary and
  aligned C++ allocations are counted, while warmed Matching tests require zero counted `new`.
  This does **not** prove absence of direct C `malloc` in all paths and is not a reason to change
  the allocation operator ABI.

### Eight tagged potential defects — source evidence is stronger than runtime proof

The eight rows group into four classes: public aggregate bounds (two), JSON partition
narrowing (one), Kubernetes Lease timestamp parsing (two), and default Matching capacity
configuration (three). A potential defect is **not** automatically a TP for the warning's
style check, and some behavior below is reproduced only in a standalone harness.

| Tagged source site | Observed risk and required evidence |
| --- | --- |
| `deterministic_matching_core.cpp:405` | `CoreInstrument::symbol_length` is public and the core constructor does not recheck its limit against the 12-byte `symbol`. `create()` bounds normal ingress, but a directly constructed value with `symbol_length=13` may make checksum iteration read beyond the array. Add a direct-constructor negative test under sanitizer before classifying a confirmed defect. |
| `matching_event_encoder.cpp:69` | `FixedText<Capacity>::length` can be changed after factory validation; `view()` does not recheck it. An oversized `trading_session_id` may lead to an out-of-range read during `HashInput::append`; the `size_ + value.size()` capacity expression also merits a subtraction-based overflow proof. Add a malformed public-aggregate test, 64/65 boundary tests and byte-identity assertion. |
| `market_reference_artifact.cpp:180` | The `loads[partition]` index itself has a short-circuit 0–14 guard. The earlier JSON `partitionId.get<int32_t>()` narrows a larger integer *before* that guard. A standalone test against the pinned build header reproduced `4294967297` → `1` and `4294967310` → `14` while JSON still reports an integer. Full checksum-matched artifact acceptance remains untested; add boundary/negative tests and check `matching_main.cpp:297`. |
| `kubernetes_lease_ownership_adapter.cpp:59,62` (two sites) | Lease `parse_timestamp()` accepts malformed suffixes: its original implementation, extracted without behavioral change and compiled as a standalone C++20 test, accepted `2026-01-01T00:00:00XZ`, `.12XZ`, and `.123garbageZ`. Only the first three fractional digits are consumed, although `format_timestamp` emits six. These parsing behaviors are reproduced; actual invalid API metadata or a contested Lease takeover is **not** established. Add strict RFC3339 parser tests and a fake-Kubernetes expiry/fencing regression. |
| `matching_main.cpp:177,178,180` (three sites) | Source and manifest analysis confirms a base-deployment capacity mismatch for the pinned approved `2026-08-19` market-reference artifact: 1,928 assignments over 15 partitions (128 or 129 per ordinal), per-book resting capacity 4,096, but base output ring 1,048,576 and pending limit 1,000,000. The core requires output capacity at least **1,048,578** for 128 instruments or **1,056,770** for 129, and pending limit at least 1,048,577/1,056,769 respectively. Constructors reject this **fail-closed**, rather than overflowing a buffer. The local overlay lowers resting capacity to 1,024 and sets both ring limits to 524,288, which covers up to 150 books; actual deployed PodSpecs and mounted artifacts were not examined. Add 128/129-book startup tests and review base/staging/production capacity policy. |

An adjacent **benchmark-only** concern is not itself a pointer-index defect:
`matching_capacity_benchmark.cpp:150-158` uses `std::strtoull` without explicit negative-sign
or `ERANGE` rejection. The `argv` indices are guarded. A standalone C++20 test on this
64-bit host confirmed that `-1` and an overflowing 64-bit value both become `SIZE_MAX`
and pass the parser's current rejection condition. End-to-end benchmark CLI behavior and
allocation failure have not been tested; add a focused negative CLI regression. This is a
separate parser finding, **not** a proven pointer-arithmetic warning defect.

### Ownership, performance and design

- **Ownership and initialization:** All seven member-initialization and 12 special-member sites
  were checked against factories, public aggregate construction, worker thread lifetimes and C
  handle destruction. There was no demonstrated *reachable* uninitialized read or double
  destruction along inspected production paths; public aggregates and raw-handle inner types
  still need negative misuse tests. The 17 allocation-interception warnings are in a test-only
  global `new/delete` counter and are not evidence of Matching Core hot-path allocation.
- **ABI/performance:** All 20 enum-width, three padding and five value-parameter sites were
  reviewed. No enum layout or performance tweak is authorized without object-layout/wire
  assertions, deterministic replay checksum goldens and measured startup/throughput evidence.
  The one `missing-std-forward` site intentionally invokes a named callback as an lvalue on
  each repeated ring consumption; forwarding on every iteration could change semantics.
- **Includes:** All 274 include-cleaner sites were reconciled to actual Hosted warnings.
  Of these, 155 direct-include style findings have source/header evidence. The other 119
  involve declaration-provider ambiguity, public third-party umbrella headers, generated
  protobuf imports or a missing compile check. Do not treat a direct include warning as
  authorization to delete an otherwise required C or generated header.
- **Domain constants:** All 210 magic-number sites have concrete literal/fixture/contract
  notes. Boundary constants, ring sizes, UUID/SHA text offsets and test expectations must be
  preserved unless their meanings are consolidated with byte-identity checks. In particular,
  `1469598103934665603ULL` is the *existing custom checksum seed*, not the conventional
  64-bit FNV-1a basis; silently changing it could break replay identity.
- **Aggregate and initialization style:** All 113 public-member and 91 designated-initializer
  positions are confirmed source-level style findings, not inferred runtime faults. Public
  DTO access, nested payload defaults and order arrival-sequence increments constrain any
  later refactor. The 32 const-correctness and 20 ranges-use sites were also individually
  reviewed, including mutex RAII, C pointers, test fixtures and binary protocols.
- **Other advisory rules:** Every C-array, C-varargs, scoped-lock, cognitive-complexity,
  global-state, static member and readability site has a distinct note. Intentional raw C
  boundaries and Linux union/varargs declarations cannot be mechanically replaced without
  API contract tests. No blanket check suppression or `-fix` action is justified.

## Remaining decision gates — no unreviewed rows

**Coverage is complete** for all 956 immutable warning identities. The outstanding work is
**substantive disposition and repair evidence**, not a queue of sites with no source inspection:
487 contextual warnings and eight potential defects have TP/FP or runtime behavior still
unverified. The ledger records the exact outstanding proof in each note; it does not set
warnings=0 or claim 956 safe mechanical fixes.

**Priority A:** Run bounded RED/negative tests for `CoreInstrument` length, `FixedText`
length and `HashInput`, JSON partition-id overflow, strict Lease timestamps and 128/129-book
Matching startup with the base manifest. Confirm the correct fail-closed response, preserving
Lease fencing and deterministic event bytes. A standalone parser/JSON conversion reproduction
is not equivalent to end-to-end admission, ASan/UBSan or Kubernetes correctness.

**Priority B:** Resolve 119 include ownership ambiguities against the pinned generated
protobuf and third-party public API export graph, plus 200 magic-number site decisions tied to
fixed domain/test contracts. Clarify enum-width and C API advice with scoped ABI and fixture
proof. These unresolved rows should remain explicitly uncertain until the missing evidence
exists; never reclassify a warning to false-positive merely because a usage is intentional.

**Local reproduction boundary:** Three temporary, standalone C++20 harnesses were run and
removed after execution. One used the local pinned nlohmann JSON header to reproduce
`get<int32_t>()` narrowing, one exercised the benchmark `strtoull` sign/overflow guard,
and one compiled the unchanged `parse_timestamp` function body extracted from the pinned
source, confirming malformed suffix acceptance. None ran project CTest, ASan/UBSan, Hosted
CI, or a live cluster. The `strtoull` result identifies a **separate benchmark parser issue**,
not a pointer arithmetic fault at its tagged site.

**Compatibility gate:** No C++ source, `.clang-tidy`, `.clang-format`, FIX/Kafka/protobuf
wire representation, ABI, CI trigger or mandatory blocking subset has changed. A future fix
must start from a focused failing test and retain existing single-writer, bounded capacity and
Lease fencing invariants. Keep #214 AC5 separately subject to its developer approval and
negative/allowed fixtures; do not blanket-`NOLINT`, suppress checks or run `-fix`.

**Issue boundary:** [#210](https://github.com/WenHsuanYu/SimpleMatch/issues/210) remains
**OPEN** because full TP/FP and safe-change disposition is not complete, despite 956/956
source review coverage. Parent [#209](https://github.com/WenHsuanYu/SimpleMatch/issues/209)
AC-09 is still partial; [#214](https://github.com/WenHsuanYu/SimpleMatch/issues/214)
AC5 remains pending. No acceptance checkbox is changed by the source-review count alone.

## Publication and workspace

The initial evidence commits `0f90700e` and `77019c33` and the first 207-site review
`2910dcd6` are already ancestors of the canonical `master`. The temporary local and remote
`evidence/issue-210-hosted-triage-20261117` branches were removed after fast-forward
publication. This complete 956-site review is a **documentation-only change on `master`**;
no additional Git worktree was created. Every source location continues to refer to
`2b7eeaea2bf1a204d5043fd72428c2d9526f6e40`, not to changed code at the documentation
commit. The source manifest and review status must be refreshed if any Native file later changes.

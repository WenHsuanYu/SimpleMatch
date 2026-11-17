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

## Verified triage coverage — still partial

The source-specific review covers **207 of 956** unique line/tag sites. An exact diagnostic site
may be reported more than once across translation units; the ledger keeps the original **2,998**
emitted occurrences. None of the counts below is a count of runtime bugs.

| Site review state | Unique sites | Meaning |
| --- | ---: | --- |
| `UNADJUDICATED` | **749** | No source-specific review; the rule-derived lane is only a work queue |
| `REVIEWED_CONTEXT_UNRESOLVED` | **162** | Source and relevant contracts examined; true/false status not fully proved |
| `REVIEWED_POTENTIAL_DEFECT_UNVERIFIED` | **3** | Plausible, localized correctness risk; no negative test or sanitizer reproduction yet |
| `CONFIRMED_STYLE_FINDING_UNFIXED` | **42** | Source confirmed the syntactic style trigger; not a demonstrated runtime defect; still unfixed |
| **Total** | **956** | **207** manually reviewed, **749** not yet reviewed |

Only the 42 confirmed style rows set `true_positive=STYLE_ONLY_CONFIRMED` and
`false_positive=NO`. All other rows retain `UNDETERMINED` in both fields, including the
three candidate defects. The original four callback/override naming sites remain in the 42.
No class-wide false-positive conclusion or bulk suppression was recorded.

Auto-triage *lanes*, including unreviewed rows, still sum to 956: ABI/protocol/buffer (291),
manual design/style (286), include dependencies (274), performance/layout (50),
initialization/correctness (17), ownership/lifetime (17), test allocation interceptors (17),
and callback/override naming (4). These lanes are **not severity rankings**.

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

### Candidate defects — **end-to-end behavior not yet reproduced**

| Tagged source site | Observed risk and required evidence |
| --- | --- |
| `deterministic_matching_core.cpp:405` | `CoreInstrument::symbol_length` is public and the core constructor does not recheck its limit against the 12-byte `symbol`. `create()` bounds normal ingress, but a directly constructed value with `symbol_length=13` may make checksum iteration read beyond the array. Add a direct-constructor negative test under sanitizer before classifying a confirmed defect. |
| `matching_event_encoder.cpp:69` | `FixedText<Capacity>::length` can be changed after factory validation; `view()` does not recheck it. An oversized `trading_session_id` may lead to an out-of-range read during `HashInput::append`; the `size_ + value.size()` capacity expression also merits a subtraction-based overflow proof. Add a malformed public-aggregate test, 64/65 boundary tests and byte-identity assertion. |
| `market_reference_artifact.cpp:180` | The `loads[partition]` index itself has a short-circuit 0–14 guard. The earlier JSON `partitionId.get<int32_t>()` narrows a larger integer *before* that guard. A standalone test against the pinned build header reproduced `4294967297` → `1` and `4294967310` → `14` while JSON still reports an integer. Full checksum-matched artifact acceptance remains untested; add boundary/negative tests and check `matching_main.cpp:297`. |

An adjacent **benchmark-only** concern is not itself a pointer-index defect:
`matching_capacity_benchmark.cpp:150-158` uses `std::strtoull` without explicit negative-sign
or `ERANGE` rejection. The `argv` indices are guarded. A standalone C++20 test on this
64-bit host confirmed that `-1` and an overflowing 64-bit value both become `SIZE_MAX`
and pass the parser's current rejection condition. End-to-end benchmark CLI behavior and
allocation failure have not been tested; add a focused negative CLI regression. This is a
separate parser finding, **not** a proven pointer-arithmetic warning defect.

### Ownership, performance and design

- **12 special-member sites:** Checked C++ ownership, abstract interfaces, unique ownership
  and thread topology. `CurlGlobalLifecycle` and its `CURL*` implementation would need an
  explicit noncopyable contract before any new copy paths; Kafka producer contexts reference
  in-flight callbacks; `MatchingRuntimeSupervisor` threads capture `this`. No actual duplicate
  destruction was found. Do not generate move/copy operations from lint suggestions.
- **20 enum-width and three padding sites:** Underlying enum widths and field placement can
  affect C++ layout and API contracts. No measured throughput or layout benefit supports changes.
  Require type/layout assertions, wire goldens, Kafka callback lifetime tests and matching
  performance comparisons before any edit.
- **Five value-parameter sites:** All are construction/startup paths. The Matching core vector
  parameter, CPU set string and Kafka constructor strings could have narrower interfaces, but
  caller ownership and public signature effects must be checked. The Kafka topic move remains
  intentional.
- **One forwarding site:** `BoundedSpscRing::consume_batch` calls a named callback repeatedly
  as an lvalue. Forwarding it as an rvalue on every iteration may change `&&`-qualified callable
  behavior. Preserve the current loop until a repeated-callback test proves a safe alternative.
- **22 include-cleaner sites:** Reviewed direct declaration/header context in the Matching core,
  input ledger and Kubernetes Lease adapter. Include ownership, pinned third-party umbrella
  headers and actual Hosted suggestions are not yet proved by compiling a proposed edit.
- **21 domain-number sites:** Three are confirmed duplicated style opportunities (12-character
  symbol limit and fixed 15 partitions); the rest still require named-constant/protocol review.
  The checksum seed `1469598103934665603ULL` is **not** the standard FNV-1a 64-bit basis;
  no checksum migration is authorized. The 100 ms supervisor poll default is overridden by
  the deployed configuration and must not be treated as the observed runtime cadence.
- **20 public-member and 15 designated-initializer sites:** These are real syntactic style
  findings, not proven false positives. Public domain aggregates expose direct field access;
  changing access or replacing nested positional initializers can alter caller/API contracts,
  inactive payload defaults and matching arrival order. The ledger records the field/aggregate
  and its source-specific constraint.

## Next review batches and decision gates

**Immediate:** Build bounded RED/negative tests for the three candidate correctness sites and
for the benchmark parser; run focused Native/ASan tests before deciding whether to amend code.
Keep source fixes separate from this source-pinned triage record. Do not run `clang-tidy -fix`.

**Remaining site triage:** **749/956** sites are still unadjudicated, mainly further
include-cleaner, magic-number, public-aggregate and initializer occurrences. Recheck each exact
`(path,line,check_tag)` against immutable source; record a short decision with concrete type,
usage and test or caller evidence. A provisional lane never substitutes for a human verdict.

**Local evidence boundary:** Two temporary, standalone C++20 reproductions were compiled and
run, without modifying tracked C++ sources or running CTest/Hosted CI. One uses the existing
`out/build/ci-fast` vcpkg nlohmann JSON header to confirm integer narrowing. The second
replays the exact `strtoull`/`size_t` guard on the local 64-bit platform. They prove those
conversion behaviors, **not** complete artifact acceptance, sanitizer detection or an end-to-end
benchmark failure. Each temporary test source and binary was removed after execution.

**Compatibility gate:** Any edit to the hot path, FIX/Kafka/protobuf bytes, enum width,
public signature, object lifetime, callback ownership or Lease fencing requires scoped tests
and explicit source/ABI/wire compatibility evidence. Do not apply blanket `NOLINT`, broad
suppression, formatting sweeps or a blocking CI gate. Mandatory checks remain part of #214 AC5
and require separate approval and negative/allowed fixtures.

**Issue boundary:** [#210](https://github.com/WenHsuanYu/SimpleMatch/issues/210) remains
**OPEN**, with the site-adjudication criterion unchecked. Parent
[#209](https://github.com/WenHsuanYu/SimpleMatch/issues/209) AC-09 remains partial.
[#214](https://github.com/WenHsuanYu/SimpleMatch/issues/214) AC5 remains pending.

## Publication and workspace

The initial 956-row evidence was committed as `0f90700e` and `77019c33` on a temporary
`evidence/issue-210-hosted-triage-20261117` branch during a previous local-tunnel outage.
After reconnection, both document-only commits were fast-forwarded into the **existing local
`master`** without creating any additional Git worktree. The temporary local branch was
removed. Further review changes belong directly to `master` and the analyzed C++ source
remains pinned at `2b7eeaea2bf1a204d5043fd72428c2d9526f6e40`. The advisory warning
inventory is historical evidence for that commit, not a claim about every future source HEAD.

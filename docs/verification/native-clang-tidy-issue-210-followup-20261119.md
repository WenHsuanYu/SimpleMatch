# Native clang-tidy follow-up — Issue #210

This note describes a narrow local remediation after the source-pinned advisory review.
Its comparison point is `2a711ac5`; it does not modify the immutable historical ledger
`native-clang-tidy-issue-210-hosted-20261117-sites.csv` or claim that all diagnostics
are fixed in the current source.

## Source-specific changes

The pinned ledger contains 956 reviewed sites: 461 confirmed style findings, 487
context-reviewed but unresolved sites, and 8 potential defects left unverified at the
pinned source. These are **historical classifications**, not a fresh current-source
clang-tidy run. The follow-up replaces 13 single-mutex `std::lock_guard` declarations
with `std::scoped_lock`: eight in `matching_runtime_supervisor.cpp` and five in
`partition_ownership_permit.cpp`. Each preserves the same mutex, lexical lifetime,
variable name, and surrounding state/notification order. Both `.cpp` files explicitly
include `<mutex>`. No public ABI, Kafka event, or lock ownership contract changed.

A separate real correctness risk was found in
`MatchingRuntimeSupervisor`'s CPU affinity parser: `0-2147483647` could expand a
huge vector before later native `CPU_SETSIZE` validation. The parser now rejects any
parsed CPU ID above `CPU_SETSIZE - 1` on Linux **before** range expansion. The
non-Linux parser retains an explicit 0–1023 bound even where affinity cannot be
applied. Public-constructor regression tests exercise the native maximum, first
unsupported single CPU and range, and the very large range.

## Verification and residual risk

The CPU constructor test initially failed for CPU IDs at `CPU_SETSIZE`, then passed
after the bounded parser change (test-first RED/GREEN from the local session). The
focused CPU/supervisor/ownership tests passed **7/7**. Full current-source Native
CTest passed **105/105**, and ASan/UBSan CTest passed **88/88**. Changed-line
`git clang-format --diff --commit 2a711ac5` and `git diff --check` were clean.

The 13 style changes are a bounded subset; the original 956-site adjudication does
not become a current-source fixed-count ledger merely because these sites changed.
Remaining correctness/ABI/ownership questions need independent source-specific
investigation. No bulk auto-fix of the 487 historical unresolved rows was attempted,
and no fresh source-wide clang-tidy run, GitHub Issue change, or CI run is asserted.

## Bounded benchmark CLI parser follow-up

The historical advisory investigation also identified a separate input-validation risk
in the Matching capacity benchmark. Its `strtoull` parser accepted `+1` and a leading
space, and could accept negative or overflowing values as `SIZE_MAX` on a 64-bit host.
The benchmark now parses unsigned decimal values directly into `std::size_t` with
`std::from_chars`. It rejects signs, leading whitespace, zero, incomplete input,
and representational overflow before constructing the benchmark workload.

The new public-CLI CTest first failed because `+1` was accepted. After the parser
change, the same test passed. It also checks leading whitespace. Both cases use
small benchmark inputs, so a future parser regression cannot launch a large workload.
Separate manually bounded commands checked `-1`, `18446744073709551616`, `0`, `1x`,
and an empty value. Each returned the expected argument error with exit code 2.

For this focused change, `full-native-dev` CTest passed **106/106**, `ci-sanitize`
CTest passed **89/89**, and offline `manifestCheck staticAnalysis` passed. This is
not a complete-source clang-tidy rerun or a deployed runtime certification.
Large but representable positive iteration counts remain a separate workload-sizing
concern; this parser fix does not set a maximum benchmark-memory budget. The original
956-row review counts remain source-pinned historical classifications.

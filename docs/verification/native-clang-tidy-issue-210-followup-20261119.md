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

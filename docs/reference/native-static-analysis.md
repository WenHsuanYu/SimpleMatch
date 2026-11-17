# Native C++ static analysis

The native CMake build uses C++20, `-Wall -Wextra -Wpedantic` with GCC/Clang,
and separate ASan/UBSan configuration. It exports
`compile_commands.json`, which lets clang-tidy analyze each translation
unit with its real compiler flags and vcpkg include directories.

## Google style and clang-tidy checks

The root `.clang-format` uses `BasedOnStyle: Google` for C++ formatting.
Formatting is a separate operation from clang-tidy. This policy does not
reformat existing Native sources or add a repository-wide formatting gate.

The root `.clang-tidy` enables the following categories:

- `clang-analyzer-*`
- `modernize-*`
- `performance-*`
- `readability-*`
- `cppcoreguidelines-*`
- `misc-*`
- `google-*`

The original local clang-tidy 21.1.8 pilot selected 333 checks. After the
developer-approved Google leading-return-style exception for
`modernize-use-trailing-return-type`, the same LLVM release selects **332**.
All seven positive check families remain enabled. The set depends on the
LLVM release; overlapping diagnostics are not independent defects.

The selected checks are **report-only during initial adoption**.
`WarningsAsErrors` remains empty. The C++ Google formatting preset and
`google-*` diagnostics are complementary, not interchangeable.

## Reproducing the report

Use the existing vcpkg root and the opt-in native preset:

```sh
export VCPKG_ROOT=/path/to/vcpkg
cmake --preset clang-tidy-report
cmake --build --preset clang-tidy-report --parallel 2
```

The build preset explicitly targets `simplematch_clang_tidy_report`;
the target is not part of `ALL`, `CTest`, `nativeTest`, or `verifyAll`.
The target builds its Native prerequisites and invokes `run-clang-tidy`
against the compilation database. It analyzes handwritten
`matching-engine/src/*.cpp`, `matching-engine/tests/*.cpp`, and the
project-owned headers included by those files. Generated protobuf
`*.pb.cc` and vcpkg/third-party headers are not analysis entry points.
The analyzer presets disable unused C++20 module dependency scanning.
With local GCC 15 and CMake 4.4, that scanner adds GCC-specific
`-fdeps-format`, `-fmodule-mapper`, and `-fmodules-ts` arguments
which clang-tidy 21 cannot parse. This toggle changes only the
analysis presets; the ordinary Native presets retain their defaults.

To include the optional Kafka/messaging Native targets, configure and
build the dedicated full preset instead:

```sh
cmake --preset full-clang-tidy-report
cmake --build --preset full-clang-tidy-report --parallel 2
```

This second preset is available for fuller coverage. Its dependencies
are wider than the ordinary `clang-tidy-report` preset.

CI can set `SIMPLEMATCH_EXPECTED_CLANG_TIDY_VERSION` and
`SIMPLEMATCH_EXPECTED_RUN_CLANG_TIDY_SHA256` while configuring the analyzer
preset. Missing tools, a version mismatch or a runner SHA-256 mismatch
fail configuration before any analyzer execution. These opt-in checks do
not alter the existing Native, sanitizer or Kafka development presets.

You can inspect the configuration and Google formatting without
changing source files:

```sh
clang-tidy --verify-config -p out/build/clang-tidy-report matching-engine/src/input_offset_ledger.cpp
clang-tidy --list-checks -p out/build/clang-tidy-report matching-engine/src/input_offset_ledger.cpp
clang-format --style=file matching-engine/src/input_offset_ledger.cpp
```

Do not run `clang-tidy -fix` or `clang-format -i` on the whole
codebase. Isolate deliberate formatting changes from business logic
changes, and review runtime/hot-path allocation, ABI, FIX/Kafka and
protobuf contract effects before adopting automated fixes.

## Local pilot results

On 2026-11-16, clang-tidy 21.1.8 completed both report targets
successfully. The ordinary preset analyzed 23 handwritten C++
translation units (26 database entries, three generated protobuf
implementations excluded) and produced 1,274 unique
`(file, line, check tag)` locations. The full Kafka/messaging preset
analyzed 28 handwritten units (31 database entries, the same three
generated files excluded) and produced 1,544 unique locations.
Both reports had zero Clang parsing errors, but numerous warnings.

The report-preset CTest runs passed 77/77 and 85/85 tests,
respectively. All four preexisting Native presets also completed
local configure/build/CTest: `dev-debug` 77/77, `ci-fast` 77/77,
`ci-sanitize` (ASan/UBSan) 77/77, and `full-native-dev` 85/85.
These results come from an uncommitted worktree, not Hosted CI or `verifyAll`.

Local ignored records are saved under
`out/verification/native-clang-tidy/`, including `baseline.json`
with the compilation database hashes, `baseline.md` with the
diagnostic distribution, and `selected-checks.txt` with the original
333 check names (before the approved single-check exclusion). Review
the findings before changing suppression rules or converting the report
into a CI gate. The counts reflect diagnostic locations, not defects.

Before any `-fix`, review `out/verification/native-clang-tidy/fixit-review.md`
and `fixits-inventory.json` from the local 2026-11-16 pilot. A read-only
28-TU fix-it export found 919 diagnostic identities with replacements, but
531 were trailing-return rewrites contrary to Google's usual preference
for leading return types. Other suggestions include shared-header edits
and 34 same-position alternatives. No automatic fixes were applied.

## Enforcement status and next gate

The existing Native CI performs `ci-fast` configure, build, and CTest.
It does not run the analyzer. The separate
`.github/workflows/ci-native-clang-tidy.yml` workflow runs the full
Kafka/messaging analyzer preset in **advisory-only** mode on Ubuntu 24.04.
It pins LLVM `clang-tidy`/`clang-format` 21.1.6 PyPI wheels and the
matching upstream `run-clang-tidy.py` script by SHA-256. That version is
deliberately distinct from the original local 21.1.8 pilot: a 21.1.8
`clang-tidy` wheel was not available when the CI pair was selected.
The workflow records its actual enabled checks instead of transferring
version-specific counts between toolchains.

The workflow analyzes only handwritten Native translation units from a
real compilation database. Its retained, bounded artifact includes
source/tool fingerprints, selected checks, warning counts and at most
200 normalized repository-relative warning locations (no diagnostic
messages or source snippets). A separate verdict receipt records each
Hosted step as success, failure or skipped so early failures can be
distinguished from unrun analysis. Raw analyzer logs, generated protobuf
and private payloads are not part of the artifact. The workflow does
not rewrite source files or enable `WarningsAsErrors`; advisory warnings
remain **unadjudicated**, not a claim of a clean baseline. A local result
does not prove Hosted acceptance: inspect the actual GitHub Actions run,
`head_sha`, job conclusion and uploaded artifact before marking it PASS.

Before enabling a blocking Native CI lint gate:

1. Pin the clang-tidy and clang-format versions used locally and in CI.
2. Capture check-name lists and findings for both ordinary Native and
   optional messaging targets.
3. Classify true positives and false positives, especially for
   protocol literals, ownership, API boundaries, and hot-path copies.
4. Select an explicit high-signal initial blocking subset with a
   negative fixture and an allowed counterpart; avoid blanket `NOLINT`.
5. Publish a source-aligned report artifact and verify that the gate
   fails for the selected violation while ordinary CTest remains green.

Reference: [C++ Best Practices CMake template](https://github.com/cpp-best-practices/cmake_template).
The template's current clang-tidy config disables `google-*`; this
repository intentionally enables Google checks instead.

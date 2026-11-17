# Spec: Google-Style clang-tidy for Native C++

Status: Historical local pilot completed. Advisory CI implemented; warning triage and blocking enforcement deferred.
Date: 2026-11-16 (Asia/Taipei).
Method: `to-spec`, `codebase-design`, and `code-review`.

The implementation later gained a dedicated advisory workflow. Local commit
`6d1ff924` (2026-11-17) consolidates its current CI design. It is not a
Hosted CI acceptance result. See [Native static analysis](../reference/native-static-analysis.md)
for the current implementation contract. The evidence below records the
original 2026-11-16 pilot and must not be treated as a current-source baseline.

## Problem Statement

Before this pilot, the Native C++ build enabled `-Wall -Wextra -Wpedantic` and had
AddressSanitizer/UndefinedBehaviorSanitizer presets. It exported a CMake
compilation database but had no repository-level clang-tidy policy,
Google-style formatting configuration, or clang-tidy build integration.
Compiler warnings cannot replace higher-level static checks for ownership,
readability, modern C++ practices, or suspicious code.

The developer requests Google style and checks from `clang-analyzer-*`,
`modernize-*`, `performance-*`, `readability-*`,
`cppcoreguidelines-*`, and `misc-*`. Google `google-*` checks and
Google clang-format formatting are also part of the intended style.

## Solution

Provide one root clang-tidy configuration enabling exactly those seven
named check families, including `google-*`, without blanket disabling
any requested family. Use the Google preset for formatting and for
clang-tidy fix formatting. Analyze handwritten Native C++ translation
units and owned headers, not CMake-generated protobuf implementations
or third-party/system headers.

Wire clang-tidy to CMake behind an opt-in configuration so existing
Debug, CI, sanitizer, and full-native presets remain unchanged.
First collect real findings and classify false positives. Promote the
analysis to a default blocking CI gate only after a clean, reviewed
baseline and a reproducible toolchain/version decision.

## User Stories

1. As a Native contributor, I want the six requested check families so that correctness, modernization, performance, and readability defects surface.
2. As a contributor, I want Google C++ conventions so that style is consistent and independently inspectable.
3. As a maintainer, I want a fixed root policy file so that local developers and CI use the same check selection.
4. As a reviewer, I want the selected checks listed and version-validated so that wildcard behavior is not silently misunderstood.
5. As a Native developer, I want CMake to invoke clang-tidy when explicitly requested so that the compile commands and dependency paths stay correct.
6. As a CI maintainer, I want the existing Native builds unchanged until the baseline has been reviewed,
   so that an opt-in analyzer does not break unrelated acceptance.
7. As a maintainer, I want generated protobuf code excluded so that machine-generated formatting is not treated as handwritten code debt.
8. As a contributor, I want third-party headers excluded from reported diagnostics so that findings are actionable within this repository.
9. As a reviewer, I want an evidence-backed findings inventory so that rule activation does not imply that the current source is already compliant.
10. As a maintainer, I want the analyzer version recorded so that differences in available checks and diagnostics are attributable to the toolchain.
11. As a developer, I want formatting changes to remain explicit so that configuring a formatter never silently rewrites Native implementations.
12. As a maintainer, I want a documented CI ratchet decision so that report-only adoption is not misrepresented as full enforcement.

## Implementation Decisions

- Use one root clang-tidy YAML configuration, Google `google-*`
  checks, and Google clang-format style.
- Enable `clang-analyzer-*`, `modernize-*`, `performance-*`,
  `readability-*`, `cppcoreguidelines-*`, `misc-*`, and `google-*`.
- Keep warnings visible without upgrading all existing findings to
  build errors before triage.
- Make CMake integration opt-in through a dedicated Native preset.
  Existing presets and Native CI paths continue to build normally.
- Use an opt-in, non-default CMake custom target that invokes
  `run-clang-tidy` with its compilation database. Restrict the
  selected translation units with a source-path regex rather than
  setting `CXX_CLANG_TIDY` on targets that also compile generated
  protobuf files.
- Preserve the existing C++20 standard, Ninja/vcpkg dependency model,
  Native target graph, compiler warnings, and ASan/UBSan controls.
- Do not copy the reference template's `-google-*` exclusion; that
  conflicts with this repository's explicit Google-style choice.
- Do not auto-apply `--fix` or `clang-format -i` to existing sources.
- Keep any future suppression justified per rule and bounded to
  a named source construct, rather than a blanket ruleset exclusion.
- Disable unused C++20 module dependency scanning in analyzer-specific
  presets only. The local CMake/GCC combination otherwise adds
  GCC-only module-scanner options that clang-tidy cannot parse.

## Testing Decisions

The primary seam is a Native CMake build generated with compile commands.
It must invoke clang-tidy with the repository policy on a handwritten
translation unit and must not invoke clang-tidy on generated protobuf
files. Configuration validation is a separate, inexpensive seam.

- **AC-01:** Both root style files are parseable by installed LLVM tools.
- **AC-02:** The enabled clang-tidy check list includes every requested
  family plus Google checks, with no global family exclusion.
- **AC-03:** Google style is available to clang-format and to clang-tidy.
- **AC-04:** Existing Native CMake presets remain unchanged in behavior.
- **AC-05:** The opt-in preset activates clang-tidy; missing analyzer
  executables produce a clear configure failure.
- **AC-06:** Generated protobuf implementations are not analyzed;
  handwritten sources and owned headers remain in scope.
- **AC-07:** A representative owned source file is analyzed with a real
  compilation database; findings and tool version are recorded.
- **AC-08:** An unmodified standard Native build and existing CTest
  behavior remain unaffected.
- **AC-09:** A diagnostics inventory distinguishes actionable findings,
  style-only results, third-party effects, and analysis errors.
- **AC-10:** The current CI enforcement status is documented precisely;
  no Hosted or mandatory gate is claimed without a real run.
- **AC-11:** The change does not modify business behavior, generated
  code, dependencies, Docker, or canonical Kubernetes resources.
- **AC-12:** Final source diff, user WIP preservation, and test evidence
  are reviewed along Standards and Spec axes.

## Local Acceptance Evidence (2026-11-16)

This is evidence from the uncommitted local worktree, based on HEAD
`96d4b005594e67fe1698ff80b458edf0ef8c375d`. It does not prove
Hosted CI or a source-aligned published artifact. The installed
`clang-tidy`/`clang-format` version is LLVM 21.1.8; CMake is 4.4.4,
and GCC is 15.2.0. All seven selected check families expand to
333 checks on this toolchain, including 19 `google-*` checks.

| Validation | Ordinary scope | Full Kafka/messaging scope |
| --- | ---: | ---: |
| Compilation database entries | 26 | 31 |
| Handwritten C++ entrypoints analyzed | 23 | 28 |
| Generated protobuf `.pb.cc` files excluded | 3 | 3 |
| Project warning lines during analysis | 3,968 | 5,270 |
| Unique `(file, line, check tag)` diagnostics | 1,274 | 1,544 |
| Owned files with findings | 36 | 43 |
| Analyzer parsing errors | 0 | 0 |
| clang-tidy report target | PASS (exit 0) | PASS (exit 0) |
| CTest with the analyzer preset | 77/77 PASS | 85/85 PASS |

Each `check tag` means the complete bracketed diagnostic identifier.
For example, a combined magic-number identifier is counted once at
one file/line; headers included by multiple TUs are deduplicated.
An additional compiler warning appears before the analyzer starts
in each build log and is not included in the table. Clang-tidy's
per-TU suppressed external-header counts are not project defect counts.

All four original configure/build/CTest paths were run separately in
this worktree: `dev-debug` 77/77, `ci-fast` 77/77,
`ci-sanitize` (ASan/UBSan) 77/77, and `full-native-dev` 85/85.
Each configure and build exited 0. None was changed by the new
opt-in cache option or the two added reporting presets.

The first analyzer run failed because CMake 4.4/GCC 15 inserted
GCC-specific C++20 module-scanning arguments into the compilation
database. Only the two new analyzer presets now disable unused
module scanning, and both reports subsequently succeeded. The first
ordinary report-preset CTest result was 76/77 because its capacity
benchmark executable had not been built. The report target now
depends on that executable; rerun was 77/77 PASS.

The local ignored evidence is under
`out/verification/native-clang-tidy/`: `baseline.json`,
`baseline.md`, `owned-native-inputs.sha256`, `selected-checks.txt`,
the two completed report logs, ordinary and full CTest logs, and
the original-preset configure/build/CTest logs. `baseline.json` records
SHA-256 fingerprints for the two compilation databases, tool policy,
and reports. The manifest hashes 44 owned C++ source/header files;
its SHA-256 is
`2a68e10230b3d3edc30de5fb88eb17b108df7067a59b90ad512227b8ef56ca0f`.
These ignored local files are not a published, retained CI artifact.

### Acceptance status

| Criterion | Status | Evidence or limitation |
| --- | --- | --- |
| AC-01 | PASS | Both LLVM policy files parse; clang-tidy configuration verification succeeds. |
| AC-02 | PASS | All seven requested families enabled; 333 named checks recorded. |
| AC-03 | PASS | Root clang-format uses Google; clang-tidy `FormatStyle: google`. |
| AC-04 | PASS | All four original presets configured, built and passed their own CTest suites. |
| AC-05 | PASS | Both opt-in targets ran; missing tools use CMake `find_program(REQUIRED)`. |
| AC-06 | PASS | 23/26 and 28/31 handwritten TUs selected; owned header findings included. |
| AC-07 | PASS | Real compile DB and LLVM version recorded; both reports exit 0. |
| AC-08 | PASS | Default ci-fast configure/build and 77/77 CTest passed. |
| AC-09 | PARTIAL | Representative risk classes identified; full actionable/false-positive triage pending. |
| AC-10 | PASS | Report-only; no Hosted analyzer run, CI gate, or Werror claim. |
| AC-11 | PASS | No Native business source, generated protobuf, Docker, or cluster edits. |
| AC-12 | PASS | Independent Standards/Spec review and local diff, scope, and tests inspected. |

The findings inventory identifies a conflict between Google's
leading-return-type preference and `modernize-use-trailing-return-type`.
It also identifies candidate mechanical fixes for named parameters,
operator parentheses, and raw strings. Other checks require correctness,
ownership, or performance review (`cppcoreguidelines-narrowing-conversions`,
`cppcoreguidelines-pro-type-member-init`,
`performance-unnecessary-value-param`). Third-party diagnostics
are suppressed by the configured header filters. None of these
untriaged findings are asserted to be confirmed defects; full
actionable/false-positive disposition remains open under AC-09.

### Read-only fix-it applicability review

The full 28-TU run of `run-clang-tidy -export-fixes` completed with exit 0 and
produced 28 YAML files. The export contains 5,270 diagnostic occurrences,
1,636 distinct `(file, byte offset, check)` identities, and 919 identities
with proposed replacements. The 1,636 count differs from the earlier
1,544 line-based combined-tag count because the identity definitions differ.
There are 1,777 distinct replacement operations, with 34 same-position
alternatives that need review before attempting any combined application.

Among the 919 fixable diagnostics, 531 are trailing-return rewrites.
Google C++ Style Guide normally prefers leading return types, so those
automatic rewrites conflict with the requested style. None were applied.
The local ignored `fixits-inventory.json`, `fixit-review.md`, and
`fixits-full/*.yaml` record the evidence and per-check risk classification.
AC-09 remains PARTIAL: fix-it applicability does not prove a true-positive
or false-positive disposition for every warning, and no Native source changed.

## Out of Scope

- Refactoring all historical Native C++ to pass every enabled check.
- Enabling `WarningsAsErrors: '*'` immediately.
- Mass formatting or automatic fixes.
- Importing the template's full CMake/CPM/dependency architecture.
- Replacing existing CTest, sanitizer, or Java quality gates.
- Enabling new Java PMD/Checkstyle/ArchUnit rules without the separate
  developer approval and evidence required by Java quality policy.

## Further Notes

- Reference: https://github.com/cpp-best-practices/cmake_template
- Reference policy: https://github.com/cpp-best-practices/cmake_template/blob/main/.clang-tidy
- LLVM checks: https://clang.llvm.org/extra/clang-tidy/checks/list.html
- CMake linting: https://cmake.org/cmake/help/latest/prop_tgt/LANG_CLANG_TIDY.html
- CMake's source-level `SKIP_LINTING` property is available since 3.27.
- The local analyzer initially observed is clang-tidy 21.1.8.
  The precise set of available wildcard-matched checks is
  version-dependent, so diagnostic baselines must record the version.

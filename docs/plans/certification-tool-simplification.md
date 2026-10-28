# Certification tool simplification

The owner approved this sequence on 2026-10-28:

1. Review duplicate source-string and source-line-count checks as one batch.
2. Move Query evidence evaluation and its tests to Java.
3. Assess how much certification evidence reuse to retain.

## Acceptance criteria

- Apply subtraction before migration.
  Retain a responsibility only when it serves a current requirement or a demonstrated recurring need.
  Remove temporary debugging probes and duplicate checks when their purpose ends.
  Do not translate them solely because they already exist.
- Remove duplicate assertions about implementation names and source size.
  Preserve tests of observable outputs, failure status, process ownership, and credential transport.
  Record necessary checks that still need a behavior-based replacement.
- Preserve the twelve Query verdict checks, their names, their order, and the contract version.
  Preserve the JSON verdict and a nonzero exit status for rejected evidence.
  Missing or malformed evidence must never produce PASS.
- Test Query evaluation through its evidence-directory interface.
  Preserve producer tests for FIX completion correlation and restoration failures.
  Remove the replaced jq evaluator and shell verdict cases.
- Keep deployment orchestration in its current runner.
  Do not add a container dependency to a verifier that only reads JSON files.
- Use directory filters for affected CI workflows.
  Preserve unrelated worktree changes.
- Assess certification phase planning and evidence reuse against current declarations and maintenance responsibilities.
  Do not weaken the existing reuse contract during the Query migration.

## Design

Query evaluation accepts retained evidence and returns a deterministic verdict.
Its tests use the same interface as the runner.
Jackson parses evidence, and JUnit tests successful and rejected scenarios.
These libraries already exist in the project.

A dedicated executable under `tools/query-evidence-verifier` keeps this operational code outside the Query service runtime.
The existing verifier executable owns Risk-to-Matching protocol observations and carries Kafka and gRPC dependencies.
Adding Query file evaluation there would join unrelated operational responsibilities.
The new executable uses the existing Gradle conventions and version catalog.

Source assertions cannot prove that a runner executed an operation or restored a workload.
Tests of generated evidence, command results, and owned-process cleanup provide stronger regression coverage.
Retain a source-order assertion temporarily only when its requirement remains necessary and lacks a behavior test.

## Verification boundary

This change requires local contract tests, the Java verifier suite, and repository Java static analysis.
It does not change deployment manifests, business services, or the certification acceptance rules.
Fixture results do not constitute a new deployed certification result.

## Source-check review

The review covers the 39 shell tests for certification planning, deployment runners, critical consumers, and Query tools.
It distinguishes assertions about source text from assertions about command output.

| Decision | Examples |
| --- | --- |
| Remove duplicate implementation checks | Function names, module references, repeated observer fields, dependency declarations, and source-line limits. |
| Preserve behavior checks | Planner decisions, changed-input fingerprints, generated manifests, reset CSV contents, failure propagation, process cleanup, and token transport. |
| Retain unique guards temporarily | Restoration before PASS publication, trading-day validation before FIX release, immutable input creation, and fault sequencing. |

The first batch updates the affected test scripts together.
The Kafka reset test now observes the uploaded CSV and the actual `--from-file` command.
Missing boundary evidence must prevent all Kubernetes commands.

The Job propagation test now substitutes the native build command and checks that execution reaches the failed prerequisite.
Previously, an unrelated native build failure could satisfy its expected nonzero status.

Verification also reproduced an existing placement-probe defect against the unchanged baseline.
The jq expression bound a boolean result as the container object.
Grouping the container selection fixes that defect.
The placement suite also rejects missing and duplicate application containers.

The focused-diagnostic jq validator had the same binding defect.
Grouping its result binding preserves the existing digest and field comparisons.
The complete contract run exposed this defect on a clean checkout.

The observation fixture already checks command order through its event log.
Remove its duplicate timestamp-order and elapsed-delay assertions.
Wall-clock adjustments make those assertions unstable.
The separate timing fixture checks timestamp arithmetic with fixed inputs.

The [reuse comparison](certification-reuse-alternatives.md) records the separate assessment of certification phase planning and evidence reuse.

## Query migration

The standalone [Java verifier](../../tools/query-evidence-verifier/README.md) owns Query evidence evaluation.
The shell runner builds it before deployment mutations and calls it with the evidence directory.
The existing environment restoration and final verdict publication remain in the runner.

JUnit retains cases for the twelve current certification requirements and meaningful interface failures.
It does not translate every historical fixture mutation or duplicate standard JSON parser tests.
The existing evidence test retains FIX completion correlation and restoration-producer failures.
The migration removes the jq evaluator and its shell verdict suite.
It also removes duplicate tests that inspect runner source.

## Decision on evidence reuse

Keep fresh live observations, source and image identity, explicit partial-run status, and owned-resource cleanup.
Keep Gradle and container-build caches for deterministic build work.
Do not create a Java planner that preserves all current custom reuse behavior.

The next simplification should remove custom historical PASS reuse for cheap static checks.
Retain custom artifact reuse only when measurements establish a necessary benefit beyond existing build caches.
Retire one-time measurement and debug tools when no current workflow needs them.
This decision favors less custom machinery over preserving every optimization.

The current phase declarations still enforce the existing reuse contract.
Implement its reduction as one aligned change to phase declarations, fingerprints, evidence, tests, and specifications.

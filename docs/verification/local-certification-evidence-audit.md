# Local Certification Evidence Audit

**Historical audit, as of 2026-10-18.** For the replacement current verification baseline, read
[Current development verification](current-local-certification.md). The original r13/r14 findings below
remain unchanged; the new run cannot retroactively complete a historical run.

Audit date: **2026-10-18**. Repository snapshot: `593c127cea5ae28e7561e8c011ad4f49940f8bd5`.
This record reconciles historical evidence available in this workspace. It does not execute
certification, change the original run artifacts, or certify the current runtime.

## What the records establish

A report is a human-readable summary. A phase result records one completed action; a plan entry
records an intended action and is not proof of completion. The aggregate manifest ties the phase
results and their identities together. Source revision identifies the tested version, while input
fingerprints and output identities supported the historical cache contract.
The [current execution contract](local-certification-phase-dag.md) retired that cache on 2026-10-28.

The paths below are generated local evidence under `out/certification/`, not tracked repository
documents. Availability describes this workspace on the audit date; another checkout may not
contain these files. A complete historical record retains its original source and claim boundary.

| Historical run or reference | Availability and observed results | Claim supported by this audit |
| --- | --- | --- |
| `issues-158-159-20260917-r13/` | Report, aggregate manifest and all 61 referenced phase results exist and agree: 61 PASS; 38 EXECUTED, 16 REUSED, 7 REVALIDATED. | Complete historical aggregate record at source `c7147e0aa232744250828be0c4cfdb5973dd1cc6`. |
| `issues-158-159-20260917-r14/` | Nine completed phase results exist: 9 PASS; 1 EXECUTED, 8 REUSED. The report says `PASSED`, but aggregate completion artifacts are missing. | Source and nine local phase records are traceable; aggregate completion cannot be confirmed. |
| `issue-119-20260904-r2/` | Report, aggregate manifest and source record exist: 59 PASS; 29 EXECUTED, 20 REUSED, 10 REVALIDATED. | Earlier historical aggregate record at source `93815beb11a8569bae5bcb10235a9d865ea9a27a`. |
| `issue-137-query-20260902-start-offset-query2/` | The cited `provenance.json` and `verdict.json` are absent. | The capability inventory preserves a historical Query claim; these original files cannot be directly checked here. |
| `issue-138-20260904-post-squash/` | The cited `evidence-manifest.json` is absent. | The capability inventory preserves a historical integrated-deployment claim; this original manifest cannot be directly checked here. |

The r13 manifest entries were compared with all 61 referenced result files, including phase,
decision, input, output and execution fields. This is a record-consistency check; live output
availability and runtime behavior were not revalidated. Phase counts belong to their respective
run definitions and are not a fixed acceptance count for future runs.

## r14: recoverable source, unconfirmed aggregate completion

The r14 run ID is `20260917-152758-500655`. Its `phases/source-preflight/result.json`,
`logs/source-preflight.log` and all nine completed phase results consistently identify source
`cf4e8f234c5aeeb271df60f07b89a6f25ca0e36d`. The missing top-level `source-revision` file therefore
does not mean that the historical source is unknown.

The nine results cover source preflight and eight reused static/configuration/inventory checks.
`plan.json` contains ten entries; the tenth is `local-image-build/account-service`, marked
`EXECUTE`, without a corresponding result. Its log reaches `bootBuildImage` and builder-image
pulling, with no final success, failure or exit status. `run-context` selects no build, Compose or
Kubernetes skip, but that execution intent does not prove those stages completed.

The run lacks `evidence-manifest.json`, `source-revision`, `local-images.lock`,
`verifier-image-identity` and `retained-namespace`. Consequently the report's `PASSED` string is
insufficient to establish a complete aggregate PASS. The available files do not distinguish an
interrupted execution from later evidence loss or another cause; they do not establish a current
runner defect. The original summary remains unchanged as historical evidence.

## Disposition and future verification

Use r13 as the complete historical aggregate record directly inspected in this audit, with its
source revision stated. Use the [current certification record](current-local-certification.md)
for the replacement current baseline. Keep r14 classified as **aggregate completion unconfirmed**, while retaining
its nine phase records. This classification concerns evidence availability and does not reopen the
accepted [Phase 1 capability inventory](../status/phase-1-capability-inventory.md).

Recovery of a historical aggregate record requires original material from the same run ID:
phase results, source, output identities and completion records that can be checked together.
Do not fill missing history with another run's results, the current HEAD or a newly executed test.
Any recovered index must identify its reconstruction basis and preserve the original files.

For a new claim about the current runtime, use a new source-aligned run under the
[current execution contract](local-certification-phase-dag.md).
Every selected phase executes. Explicit skips preserve partial scope.
A new result has its own run identity and cannot retroactively complete r14.

Documentation navigation changes alone do not require deployment verification. Changes to phase
semantics, acceptance rules or runtime inputs require classification under the existing contract.
Follow the [local certification runbook](local-production-like-certification.md) when a new runtime
claim is needed; this audit does not start that workflow.

## Audit validation

The audit checked the run records above without executing their phases. A file-content inventory
of all 957 r13 files and 31 r14 files was captured before editing the documentation and compared
afterward to verify that the original evidence remained unchanged. Repository Markdown links and
fragments, the existing link-checker regression and diff whitespace checks validate this change.

# S0 Shell reduction inventory

Status: completed and reviewed analysis for [Issue #197](https://github.com/WenHsuanYu/SimpleMatch/issues/197).
Approved specification: [Issue #196](https://github.com/WenHsuanYu/SimpleMatch/issues/196).
Source baseline: `7b9d2fd035632aa6797ad1d771a90c96b5062a87`.
Analysis date: 2026-10-29.
S0 changes no executable behavior and claims no runtime or certification PASS.

## Deliverables

| Artifact | Purpose |
| --- | --- |
| [Execution and deletion plan](execution-plan.md) | Acceptance, per-slice deletion arithmetic, scenario batches, findings, and blockers. |
| [Requirement ledger](requirements.md) | Twenty-six requirement groups with authority, current consumers, future owners, and evidence/deletion conditions. |
| [File disposition ledger](script-ledger.md) | All 170 baseline Shell files, assigned requirement IDs and deletion owners. |
| [Machine-readable ledger](script-ledger.json) | Exact hashes, line budgets, all textual reference locations, dynamic-import notes, and auxiliary language inventory. |
| [Resource and interface assessment](resources.md) | Offline resource calculations, hosted-runner mismatch, proposed argument meanings, and unmeasured inputs. |
| [Bounded-check record](evidence.json) | Syntax/fixture results, reproduced defects, default phase selection, and evidence limits. |

## Result

All 170 baseline files and 34,923 physical lines have planned dispositions.
All current Shell bytes matched the frozen source commit during the audit.
No later tracked Shell addition or deletion existed at that snapshot.
The scan covered 1,610 tracked text files and identified seven Shell paths with no literal inbound reference.
Those paths still require semantic, dynamic-import, and manual-entry review before deletion.

The plan retains 28 existing operation/tool files within 4,993 lines.
It assigns 142 files and 29,930 lines to replacement, alias consolidation, or obsolete-shape retirement.
Only eight files and 98 lines are direct alias/obsolete-shape removals.
Most reduction depends on implemented replacements and removal of their former scaffolding.

An allowance of six new operation helpers and 1,991 lines preserves the target of 34 files and 6,984 lines.
This is a concrete deletion budget, not a measured implementation-size prediction.
The hard minimum remains at most 51 files and 10,476 lines.
Each later slice must account for new files, helper dependencies, and hidden language relocation.

## Material findings

The full declared Kubernetes profile needs 41.7421875 GiB memory and 6.875 CPU requests at its larger stage.
It exceeds the standard public GitHub-hosted Linux runner before host overhead.
The [resource note](resources.md) documents the calculation, official runner source, and eligible alternatives for later evaluation.
No larger runner purchase or self-hosted switch follows from S0.

The current complete runner selects 62 phases but does not automatically run all six dependent business/restoration scenarios.
The new complete entry point must include their requirements explicitly.
Two current necessary-check defects need bounded remediation: obsolete topic function lookup and ignored Compose cleanup failure.
An archived phase check also produces a false positive for valid internal observation HTTP.
Query #194 is closed, so older pending-status prose cannot establish current tracker state.

## S0 verification scope

All 170 Shell syntax checks passed.
Two focused verdict contracts passed, as did the historical Phase 6/7 shape check.
The topic-cutover and archived Phase 5 checks failed for the recorded static causes.
The real cleanup function returned zero despite a simulated Compose cleanup exit of 17.
The stub performed no Docker or cluster operation.
No current deployment health, full-local success, or production readiness follows from these checks.

The first available implementation work after S0 acceptance is [S1 #198](https://github.com/WenHsuanYu/SimpleMatch/issues/198).
[S4 #201](https://github.com/WenHsuanYu/SimpleMatch/issues/201) also depends only on S0.
The native issue graph remains authoritative for execution status and blockers.

## Review

Independent Standards and Spec reviews completed with no remaining findings after corrections.
The corrections repaired a Markdown table cell and moved final shared-path deletion to the final-consumer batch.
They did not change runtime code or reduce the required coverage.
No new executable code, duplicated verifier, parameter bundle, or speculative framework was introduced in S0.
Remaining design risks are the scale of S5, hosted-runner capacity, and unmeasured runtime inputs recorded in the plan.

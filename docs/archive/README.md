# Archived Documentation

This directory contains completed execution records, superseded designs, and historical
investigations that are no longer current implementation or certification authorities. The files
remain versioned so their decisions, evidence, and failure analysis stay recoverable without
competing with the active documentation entry points.

Use the current documents for implementation and verification:

- [Phase 1 remaining-work inventory](../routing-policy-remaining-work.md) is the current capability,
  dependency, and evidence authority.
- [Target documentation index](../../services/docs/README.md) is the current architecture and
  contract entry point.
- [Production-like certification runbook](../production-live-certification.md) is the current
  local certification procedure.
- [Deployment-test lessons](../agents/deployment-test-lessons.md) is the current prevention
  checklist for deployment failures.

## Archive inventory

| Archived document | Why it is archived | Current authority or replacement |
| --- | --- | --- |
| `phase-0-baseline.md` | Historical 2026-07-27 baseline and module inventory. | Remaining-work inventory and Git history. |
| `phase-1-build-dependency-policy.md` | Completed build-policy execution gate. | [Dependencies](../dependencies.md) and [development workflow](../development-workflow.md). |
| `phase-4-data-dictionary.md` | Historical clean-install V1 schema record; later service migrations and platform specifications are authoritative. | [Platform data model](../../services/docs/platform/data-model.md), [database architecture](../../services/docs/platform/database-architecture.md), and migrations. |
| `phase-5-market-reference-publisher.md` | Explicitly superseded runtime Market Reference publisher design. | [ADR 0008](../adr/0008-offline-market-reference-artifact.md), [artifact contract](../market-reference-artifact-contract.md), and [approval workflow](../market-reference-approval-workflow.md). |
| `quality-ratchet-verification.md` | Point-in-time 2026-08-03 analyzer evidence. | [PMD quality policy](../pmd-quality-policy.md) and the current CI lifecycle. |
| `risk-service-submission-refactor-plan.md` | Completed refactor plan and checklist. | [Risk service specification](../../services/risk-service/docs/README.md) and [parameter-safety refactor](../refactoring/domain-parameter-safety-refactor.md). |
| `readme-documentation-refactor-audit.md` | Completed documentation-navigation audit. | [Target documentation index](../../services/docs/README.md), root README, and the Markdown link checks. |
| `readme-documentation-refactor-spec.md` | Completed documentation reorganization specification. | The current documentation indexes and repository workflow rules. |
| `taiwan-event-driven-refactor-plan.md` | Historical master plan whose status and phase checklist no longer describe the completed Phase 1 release. | Remaining-work inventory, cross-cutting transaction policy, ADRs, and certification runbook. |
| `local-production-like-kubernetes-workload-startup.md` | Historical startup investigation; prevention checks and executable runbooks now own the workflow. | [Production-like certification runbook](../production-live-certification.md), [deployment lessons](../agents/deployment-test-lessons.md), and [registry lifecycle](../local-registry-resource-lifecycle.md). |

The archive is not a second source of truth for implementation status or release evidence. The
transaction-criteria scripts intentionally read the archived plan to check that its historical
acceptance sections remain structurally complete; that is a static contract check, not a claim that
the archived checklist is the current implementation frontier. New implementation, deployment, or
certification links must target the active documents above; the forwarding pages at the two formerly
referenced paths exist only to preserve older links.

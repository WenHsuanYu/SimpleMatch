# Archived development contract scripts

This directory contains shell checks that were useful while specific staged
refactors were being assembled. They inspect source or document structure,
completed phase checklists, and delegation boundaries rather than normal
operator workflows.

They are not operator entry points and are not part of the normal local-lab
workflow. CI may still execute selected archived checks as regression evidence.

Archived here:

- `check-phase-5-gate.sh`: completed Phase 5 fixture and source-shape gate.
- `check-phase-6-7-gate.sh`: completed Phase 6/7 checklist and artifact gate.
- `test-local-lifecycle-safety.sh`: fail-closed observation and destructive
  ownership meta-contracts.
- `test-local-registry-resource-lifecycle.sh`: source-level contract for the
  accepted seven-step registry/resource-lifecycle design.

The functional transport, rendering, resource-report, live kind, resilience,
and cluster-manager tests remain under `scripts/` because they validate
externally meaningful behavior rather than temporary implementation shape.
`normalize-local-images-for-kind.sh` also remains active because `kind-load`
is still an explicitly supported compatibility fallback.

`check-transaction-acceptance-criteria.sh` remains active because dedicated CI
continuously enforces that cross-cutting transaction policy. The Account v2
cutover guard also remains active because it prevents production callers from
regressing to the retained v1 RPC.

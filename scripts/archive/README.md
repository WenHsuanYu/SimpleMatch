# Archived local lifecycle development contracts

This directory contains shell checks that were added while the local registry
and resource-lifecycle refactor was being assembled. They inspect source-level
contracts and delegation boundaries so intermediate refactors do not silently
change the intended behavior.

They are not operator entry points and are not part of the normal local-lab
workflow. CI may still execute them as regression evidence.

Archived here:

- `test-local-lifecycle-safety.sh`: fail-closed observation and destructive
  ownership meta-contracts.
- `test-local-registry-resource-lifecycle.sh`: source-level contract for the
  accepted seven-step registry/resource-lifecycle design.

The functional transport, rendering, resource-report, live kind, resilience,
and cluster-manager tests remain under `scripts/` because they validate
externally meaningful behavior rather than the temporary implementation shape
of this refactor. `normalize-local-images-for-kind.sh` also remains active
because `kind-load` is still an explicitly supported compatibility fallback.

# Certification reuse measurement retirement

The owner retired custom historical PASS reuse and its measurement wrapper on 2026-10-28.
`scripts/measure-local-certification-reuse.sh` and its dedicated test no longer exist.
A certification run now executes its selected phases and uses ordinary build-tool caches.

Use the [current execution contract](local-certification-phase-dag.md) for commands and evidence rules.
The [workflow comparison](../plans/certification-user-workflow-options.md) records the maintenance trade-offs.

Historical measurement design remains available in Git at source `6bb24f6fc28edf516c3aa6bcc45abe50fee29af8`.
Do not run two deployments solely to validate the retired cache mechanism.
Historical timing observations do not measure the current runner.

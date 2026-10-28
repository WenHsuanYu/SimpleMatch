# Local certification execution contract

The category C reduction on 2026-10-28 retired custom historical PASS reuse and same-run resume.
The existing production-like runner remains the full-certification entry point.
Its phase declarations now define dependencies, profile selection, and execution order only.

## Start a run

1. Check the required source, approved trading-day inputs, Docker daemon, and Kubernetes environment.
2. Select the full profile or explicit partial options.
3. Start the existing runner with a new evidence directory.

```shell
bash scripts/run-local-production-like-certification.sh
```

The default evidence path is `out/certification/local-production-like/<run-id>/`.
Set `SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR` to select another new path.
The runner rejects an existing path before daemon queries or report writes.
`--dry-run` prints selected phases and commands without creating execution evidence.

Every selected phase executes.
Gradle, BuildKit, and buildpacks retain their own ordinary build caches.
The certification runner does not infer current PASS from an earlier result.
A failed or interrupted run requires a new invocation and new runtime resources.
The owner retired `--resume`.

## Profiles and execution order

The [phase declarations](../../scripts/lib/local-certification-phase-graph.sh) select and order existing command adapters.
They preserve prerequisite ordering for migrations, topic provisioning, Connect, Open Barriers, workloads, and CDC verification.
Image publication and node import use the selected transport.
The runner does not maintain a second build cache or output-materialization layer.

`--skip-build`, `--skip-compose`, `--skip-kubernetes`, and `--matching-fleet-only` produce PARTIAL reports.
Omitted phases have no PASS result.
A partial report cannot establish a complete `full-local` certification.
A failed phase stops execution and produces a FAILED report.
Cleanup failure also prevents aggregate success.

## Current-run evidence

Each run retains these files:

- `run-context`: run, namespace, profile, and source identities.
- `source-revision`: the source commit.
- `phases/<phase-id>/result.json`: the current execution status, exit code, run identity, and source identity.
- `evidence-manifest.json`: the ordered completion inventory and shared run/source identity.
- `logs/` and scenario outputs: execution output and necessary failure diagnostics.
- `local-images.lock` and `image-lock.sha256`: the generated image lock and its recorded byte identity for registry transport.
- `report.md`: aggregate status and completed phases.

Results and completion inventories use schema version 2.
A command starts with RUNNING and finishes with PASS or FAIL.
PASS requires exit code zero.
Completion requires a valid result for every selected phase from the same run and source.
The inventory lists phase IDs instead of duplicating their results.
There is no `plan.json`, content-addressed evidence store, reusable result object, or phase completion marker.

## Retained-environment diagnostics

Focused CDC, Connect, and placement diagnostics retain their existing entry points and claim limits.
They check current namespace ownership, source scope, image identity, required PASS results, and applicable runtime inputs.
The registry image lock must match its recorded byte identity.
A focused observer executes its current verifier and writes a new diagnostic report.
Its PASS cannot upgrade an earlier aggregate result.

Current readers reject historical schema version 1 results.
Keep old reports as evidence for their recorded source and date.
Use a fresh run for a current diagnostic that requires schema version 2.
Do not relabel or rewrite an old report to satisfy the current reader.

## Ownership and cleanup

`--keep-resources` retains only the run's Compose project and disposable namespace for inspection.
Normal cleanup preserves canonical clusters and unrelated resources.
It deletes only resources whose ownership passes the existing cleanup checks.
Starting a new run does not authorize deleting an older retained namespace.

## Retired responsibilities

The reduction deletes custom phase-input fingerprints, historical PASS lookup, immutable-result objects, output lineage, cache restoration, and reuse measurements.
Their dedicated tests retire with those features.
Existing execution, partial-profile, source, image, diagnostic, and ownership behavior checks remain.

The [user-workflow comparison](../plans/certification-user-workflow-options.md) records the alternatives and maintenance trade-offs.
The [runbook](local-production-like-certification.md) owns deployment prerequisites and claim boundaries.

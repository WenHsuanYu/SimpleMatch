# Certification implementation reference

The custom cache and resume implementation retired on 2026-10-28 as part of category C.
The [current execution contract](local-certification-phase-dag.md) replaces the previous planner and evidence-store design.

The existing modules now have these responsibilities:

| Module | Responsibility |
| --- | --- |
| `scripts/lib/local-certification-phase-graph.sh` | Select phases and order their dependencies. |
| `scripts/lib/local-certification-run.sh` | Call existing runtime and build commands. |
| `scripts/lib/local-certification-framework.sh` | Execute commands, preserve failures and logs, report results, and clean owned resources. |
| `scripts/lib/local-certification-evidence.sh` | Write and check current-run results and completion inventories. |
| `scripts/lib/local-certification-images.sh` | Construct and check the current registry image lock. |

The fingerprint, planner, and generic output-materialization modules no longer exist.
The remaining phase list does not cache results or define reuse policy.
The module filenames remain stable for existing navigation.

Historical design and implementation remain available in Git at source `6bb24f6fc28edf516c3aa6bcc45abe50fee29af8`.
Historical reports retain their original format and claims.

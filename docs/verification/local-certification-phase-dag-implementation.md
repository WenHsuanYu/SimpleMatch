# Retired Shell certification implementation

Issue #202 S5 P5b removed the second 62-phase execution framework.
The former phase graph, root runner, bootstrap, Connect/Kafka/Kubernetes/job/workload
adapters, and generic execution reporter are no longer runnable.
`./gradlew verifyAll` owns complete-local selection.

The following helpers remain for read-only legacy CDC focused diagnostics:

| Helper | Retained responsibility |
| --- | --- |
| `local-certification-focused-diagnostic.sh` | Validate legacy run ownership, prerequisites and the current CDC observer. |
| `local-certification-provenance.sh` and `local-certification-runtime-provenance.sh` | Check scoped runtime/verifier source identity. |
| `local-certification-evidence.sh` | Validate legacy schema v2 phase results. |
| `local-certification-images.sh` | Validate the recorded registry image lock bytes. |

The focused diagnostic holds historical phase IDs only to check retained evidence.
It cannot plan or execute phases or publish an aggregate PASS.
The original framework is available in Git history.

# S4 manifest validation and test retirement

Owner: [Issue #201](https://github.com/WenHsuanYu/SimpleMatch/issues/201).
Program: [Issue #196](https://github.com/WenHsuanYu/SimpleMatch/issues/196).
This record applies the owner's 2026-11-02 scope amendment to the historical S0 plan.

## Accepted scope

Reduce maintenance responsibility. Migrate the core function and retain basic verification.
Do not translate historical Shell assertions individually into Java.
Retire completed compatibility checks, duplicate entry points, and tests retained only to increase coverage.
An old negative case does not require a new implementation solely because it once existed.

The delivered interface is [manifestCheck](../verification/manifest-validation.md).
Pinned Kustomize and Kubeconform validate current rendered profiles and verification workloads.
The only added project policy requires startup and readiness probes on controller workloads.
Basic negative examples prove schema errors, missing schemas, empty input, and absent probes fail.
There is no historical-predicate equivalence claim.

## Disposition

S4 retires the following paths. Their source remains available at the preceding Git revision.

| Former test | Disposition and resulting coverage |
| --- | --- |
| `scripts/test-kubernetes-overlays.sh` | Replace rendering/schema responsibility with the shared task. Retire exact configuration snapshots and individual setting assertions. |
| `scripts/test-local-kubernetes-dependencies.sh` | Retire duplicate static dependency/configuration assertions. Keep real dependency and deployed scenario verification. |
| `scripts/test-matching-kubernetes-manifests.sh` | Retire source-layout and exact manifest snapshots. Keep current rendered schemas and actual Matching ownership/fencing behavior. |
| `scripts/test-postgresql-redis-manifests.sh` | Retire standalone static snapshots. Current overlays still receive schema validation. |
| `scripts/test-kafka-kraft-manifests.sh` | Retire the static contract and dedicated fake-command/disk fixture framework. Dedicated wrong-disk and transient-metadata regressions are no longer independently covered. |
| `scripts/test-matching-topic-cutover.sh` | Retire completed v1/v2 cutover, retired-file, and Shell function/dispatch shape assertions. F-01's old function-location failure already passed in current source. |
| `scripts/test-matching-topic-profile.sh` | Retire the dedicated fixture matrix. Keep the existing topic validator and provisioner for actual operator/runtime use. |
| `scripts/test-risk-matching-e2e-manifest.sh` | Replace schema responsibility. Retire exact Job, wrapper, and image-builder shape assertions. Keep the real RM-1 Kubernetes scenario. |
| `scripts/test-risk-matching-restart-replay-manifest.sh` | Replace schema responsibility. Retire exact Job and orchestration-source matchers. Keep the actual replay entry point and verifier. |
| `scripts/test-local-production-like.sh` | Retire the legacy runner's ConfigMap failure stub and synthetic placement/provenance framework. Remove its collection caller. Keep actual deployment and RM-1 verification. |
| `scripts/test-phase1-deployment-contracts.sh` | Retire the Shell test-selection collector, its static phase, and its local/CI callers. Do not recreate its command list elsewhere. |

The Kafka startup implementation still checks persistent cluster and node identity.
Retiring its dedicated fixtures changes regression coverage, not that runtime behavior.
The reduction provides no execution evidence for those branches.
The same limit applies to retired TLS, selector, resource-value, and topic-fixture snapshots.

Existing native liveness verification and producer-evidence generation retain active consumers outside this replacement.
They are not translated into new Java tests here.
The three narrow topic operator tools remain available.
The local renderer remains compatibility for aggregate callers until S5-G, as the S0 plan requires.
S4-B transaction-policy and hook work remains outside this manifest slice.

## Caller and evidence contract

Local certification and CI call one authoritative `manifestCheck` task.
S4 removes `static-kubernetes-dependencies`, `static-matching-manifests`, `static-matching-profile`, and `static-phase1-deployment-contracts`.
Their retired assertions receive no new PASS result.
Runtime phase ordering, real scenario assertions, ownership, diagnostics, and cleanup remain in their existing owners.
Current provenance includes the replacement module and its pinned configuration.
Historical evidence retains its original commands and claims.

The [Helm assessment](../research/helm-adoption-assessment.md) recommends retaining Kustomize in this slice.
S4 introduces no chart. These offline changes provide no complete local or production certification result.
The final handoff records executed checks and exact Shell/language deltas against the source revision.

Final CDC CI exposed a bounded fixture blocker: cooperative reassignment could wait five minutes after broker loss.
The single-worker fixture now disables that delay while retaining the original recovery deadline and exact delivery assertions.
The existing RF3 scenario holds broker loss for 30 seconds to exercise the regression.
See the [failure, fix, and prevention record](../agents/deployment-test-lessons.md#run-preflight).

# Helm adoption assessment

Status: S4 assessment for [Issue #201](https://github.com/WenHsuanYu/SimpleMatch/issues/201).
Recommendation: retain Kustomize for this slice. A chart conversion requires a separate approved slice.
Sources checked: 2026-11-02. Repository source inspected at `b3405a9d3c8da82d570e982eb67cf7bac5220586`.
The source links below identify that revision, including paths that later slices may replace.
This assessment does not create a chart or establish installation, runtime, or deployment PASS evidence.

## Scope and decision criteria

The approved [migration specification](../plans/shell-test-framework-migration-spec.md#helm-and-schema-validation) separates manifest validation from Helm adoption.
This assessment identifies responsibilities a chart could replace, compatibility requirements, and the evidence needed to justify adoption.
The decision depends on removed maintenance responsibilities and a complete replacement path.
A change in file extension or a second renderer does not establish that benefit.

## Current responsibilities and possible replacements

| Current owner and source | Responsibility | Possible Helm replacement and limit |
| --- | --- | --- |
| [Environment overlays](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/deploy/k8s/README.md#L22-L42) and [local Kustomization](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/deploy/k8s/overlays/local/kustomization.yaml#L1-L46) | Assemble base resources, environment patches, generated configuration, and image references for `local`, `test`, `staging`, and `production`. | Templates and explicit values profiles could replace this assembly. The selected slice must delete its former patches and renderer callers together. |
| [Transport-aware local renderer](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/scripts/render-local-kubernetes-manifest.sh#L72-L154) and [lock profiles](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/scripts/lib/local-image-transport.sh#L161-L201) | Select a namespace, inject immutable registry references, check `full` or `matching-only` locks, and replace output atomically. `kind-load` preserves local references. | Chart values could replace the transient outer Kustomization. Lock completeness, exact image checks, transport semantics, and failed-output handling still need an owner. |
| [RM-1 profile selection](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/tools/kubernetes-tests/src/main/java/com/simplematch/tools/kubernetes/RiskCommandProfile.java#L12-L42) | Select canonical Account, Risk, Kafka, PostgreSQL, Connect, and supporting resources from the local overlay. | A bounded values profile could replace filtering if it preserves the selected resources and their policies. A parallel copy of those resources would add another source of truth. |
| [RM-1 deployment](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/tools/kubernetes-tests/src/main/java/com/simplematch/tools/kubernetes/RiskCommandDeployment.java#L28-L86) | Start dependencies, complete Flyway/topic Jobs, publish Open Barriers, start workloads, and register the Risk connector. | Hooks could implement selected Job gates. They do not remove the need to prove dependency order, connector configuration, event identity, or business results. |
| [Scenario relocation](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/tools/kubernetes-tests/src/main/java/com/simplematch/tools/kubernetes/KubernetesScenarioTarget.java#L6-L15) and [namespace lifecycle](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/tools/kubernetes-tests/src/main/java/com/simplematch/tools/kubernetes/KubernetesNamespace.java#L27-L81) | Apply run ownership and image replacements. Reject another namespace identity and wait for owned namespace/PV deletion. | A release could group chart resources. Existing run, cluster, namespace UID, and volume checks remain required until an equivalent lifecycle replaces them. |

The secure overlays contain promotion placeholders rather than executable external environments.
Their render acceptance cannot establish valid production endpoints, credentials, image availability, or external policy enforcement.
See the [environment contract](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/deploy/k8s/README.md#L39-L42)
and [staging image substitutions](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/deploy/k8s/overlays/staging/kustomization.yaml#L19-L49).

## Tool facts and design implications

Helm charts provide templates and values. A `values.schema.json` file can validate the final merged values during rendering, linting, installation, and upgrade.
This validates chart inputs. It does not prove that every generated object meets Kubernetes schemas or project configuration requirements.
See the official [chart schema documentation](https://helm.sh/docs/v3/topics/charts/#schema-files).

`helm lint --strict` makes lint warnings fail. `--kube-version` selects capabilities and deprecation checks.
`helm template` renders locally without server-side API support checks.
Design inference: retain pinned Kubeconform validation and necessary project constraints after chart rendering.
See [Helm lint](https://helm.sh/docs/v3/helm/helm_lint/) and [Helm template](https://helm.sh/docs/v3/helm/helm_template/).

Hooks can run at installation or upgrade points and use weights for ordering.
Helm waits for hook Jobs or Pods to complete, and a hook failure fails the release operation.
Hook resources are not managed with the corresponding release, so uninstall alone does not guarantee their removal.
Deletion policies or Job TTLs require an explicit design.
Design inference: a pre-install migration Job cannot depend on ordinary chart resources that Helm creates later.
Dependency preparation, failure diagnostics, and exact cleanup remain part of the deployment design.
See [Helm hooks](https://helm.sh/docs/v3/topics/charts_hooks/).

Helm offers bounded readiness waits and optional Job completion waits.
Those conditions do not establish trading, CDC, replay, or recovery correctness.
Chart tests execute annotated workloads and consider a successful container exit a successful test.
Design inference: keep business assertions in the existing scenario suite rather than duplicate them in chart hooks.
See [Helm install](https://helm.sh/docs/v3/helm/helm_install/) and [chart tests](https://helm.sh/docs/topics/chart_tests/).
These references describe tool capabilities. They do not select a Helm version for this repository.

## Compatibility requirements for a later slice

1. Preserve all supported profiles within the selected scope. Compare final resource identities, selectors, configuration, probes, RBAC, and NetworkPolicy.
2. Preserve resource, scheduling, storage, replica, and security policies. Keep local behavior distinct from staging/production promotion templates.
3. Preserve Flyway-before-workload ordering, topic preparation, Open Barrier publication, Connect configuration, and the selected scenario's observation requirements.
4. Preserve exact image-lock substitution and runtime image identity. Session, artifact, and Matching image agreement must survive rendering changes.
5. Preserve externally provisioned Secrets. Keep credentials out of committed values, rendered evidence, command logs, and diagnostic reports.
6. Define one owner for releases, namespaces, Jobs, PVCs, cluster-scoped resources, and retained data. Preserve unrelated resources and canonical clusters.
7. Define initial installation, upgrade, rollback, interrupted-operation recovery, diagnostics, and cleanup. Chart rollback cannot reverse a completed database migration by itself.

These requirements follow the current [execution contract](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/docs/verification/local-certification-phase-dag.md#L28-L78),
[external Secret/migration contract](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/deploy/k8s/README.md#L230-L272),
and [artifact/session agreement](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/deploy/k8s/README.md#L338-L351).
They are compatibility obligations, not claims that this assessment executed those checks.

## Recommendation and adoption gate

Retain Kustomize in S4 and deliver `manifestCheck` over its current outputs.
Helm could consolidate rendering and values assembly.
It cannot eliminate the current dependency, identity, ownership, and business verification responsibilities.
This assessment provides no experimental evidence that rendering benefits exceed conversion and lifecycle costs.
Keeping two active rendering paths would add profile drift and ambiguous resource ownership.
This is a design assessment based on the sources above, not a measured claim about Helm performance.

A later proposal should select one bounded deployment slice and name the exact rendering code, patches, and callers it will remove.
It must pin the Helm binary, chart dependencies, Kubernetes target, and schema inputs before acceptance.
Acceptance must include all of these checks:

1. Run `helm lint --strict` for every supported values profile with the selected Kubernetes target.
2. Render every supported profile and run schema plus project-constraint validation, including required negative cases.
3. Execute a real installation and relevant upgrade/failure paths. Retain admission, storage, scheduling, networking, image execution, and application checks.
4. Prove diagnostics, ownership, restoration, and cleanup with new evidence. A rendered-schema PASS cannot establish runtime or aggregate certification PASS.
5. Update local/CI callers and remove the old rendering path and its obsolete dedicated checks in the same delivery.

Those gates preserve [Issue #201](https://github.com/WenHsuanYu/SimpleMatch/issues/201)
and the approved [Helm migration requirements](../plans/shell-test-framework-migration-spec.md#helm-and-schema-validation).
Helm conversion remains independent of the Shell reduction acceptance gate.

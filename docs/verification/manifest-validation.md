# Rendered manifest validation

Run the shared local and CI check before deployment:

```shell
./gradlew manifestCheck
```

The task renders `local`, `test`, `staging`, and `production` with the pinned Kustomize binary.
It also validates every YAML workload in `deploy/k8s/verification/`.
It uses no Docker daemon or Kubernetes API.
JUnit reports appear in `tools/kubernetes-tests/build/reports/tests/manifestTest/`.

## Pinned inputs

| Input | Selected identity |
| --- | --- |
| Kustomize | `v5.8.1` |
| Kubeconform | `v0.8.0` |
| Kubernetes target | `1.36.1`, matching the checked-in kind node version |
| Strict schema source | `yannh/kubernetes-json-schema` revision `e0c72a1bc5f409d1685c0e1323460c8a98cd7459` |
| Content verification | SHA-256 for each tool archive and each selected schema in [manifest-check.json](../../config/manifest-check.json) |

The task requires the project JDK, `curl`, and `tar`.
It downloads build inputs beneath `tools/kubernetes-tests/build/manifest-inputs/` and checks their content hashes.
It extracts executables again from verified archives, then uses local schemas during validation.
Normal input downloads remain cached. The validation task executes on every invocation.
Changing the pinned inputs selects a separate build cache directory.
Remove a reported corrupt input from that build directory before downloading it again.

Kubeconform runs with strict validation and an explicit Kubernetes version.
Missing schemas fail. The configuration defines no custom-resource exceptions or ignored resource kinds.
A new resource schema requires an explicit source and content pin.
Any future exception also requires a reviewed owner for the uncovered validation.
See the upstream [schema-location and strict-validation documentation](https://github.com/yannh/kubeconform/tree/v0.8.0#overriding-schemas-location).

## Basic project rule and scope

Each Deployment and StatefulSet must contain a container with both startup and readiness probes.
Kubernetes schemas permit these probes to be absent, so the project checks this condition separately.
Basic rejection examples cover invalid schema, an unknown resource schema, empty input, and missing workload probes.

The owner narrowed S4 on 2026-11-02 to reduce maintenance responsibilities.
The check does not reproduce historical configuration snapshots or completed compatibility transitions.
The [S4 disposition](../plans/shell-test-reduction-s4.md) records the retired tests and reduced coverage.

Schema acceptance does not establish admission, storage, scheduling, networking, image execution, TLS enforcement, or business behavior.
Those properties require their actual runtime interfaces and current evidence.
The existing Kubernetes scenario and complete certification entry points retain their runtime checks.
This offline task does not establish a deployment or aggregate certification PASS.
Staging and production remain promotion templates with placeholders.

## Callers and Helm

Java, CDC, and lifecycle CI call this same Gradle task.
The local certification phase `static-kubernetes-overlays` calls it before resource preparation.
The typed Kubernetes build preflight and scenario task also require it before live operations.

Kustomize remains the rendering owner.
The [Helm assessment](../research/helm-adoption-assessment.md) identifies responsibilities a later approved chart slice could remove.
Such a slice requires strict lint, rendered-profile validation, runtime installation evidence, and removal of its old rendering path.

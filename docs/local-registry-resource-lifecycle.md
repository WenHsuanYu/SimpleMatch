# Local registry and resource lifecycle

The reusable `simplematch-live` kind cluster must not treat application images as permanent node-local state. Each kind node has its own containerd content store and unpacked overlayfs snapshots, so preloading the complete SimpleMatch image inventory into every node multiplies disk usage even when a workload never runs there. The repository therefore treats node-local application images as reproducible cache: publish once to the local registry, render Kubernetes manifests by immutable digest, and let only scheduled nodes pull what they need.

This design is a bounded local-lab implementation. It supports the local resilience work in #151 and #170, but it does not by itself satisfy every acceptance criterion of those broader issues or claim production HA, storage HA, automatic failover, or external certification.

## Registry-only image transport

Local Kubernetes application images use one repository-owned OCI registry:

- endpoint: `localhost:5001`
- container: `simplematch-local-registry`
- image: `registry:3`
- restart policy: `unless-stopped`
- data volume: `simplematch-local-registry-data` mounted at `/var/lib/registry`
- host publication: `127.0.0.1:5001 -> container :5000`

Each kind node has `/etc/containerd/certs.d/localhost:5001/hosts.toml` mapping that logical endpoint to `http://simplematch-local-registry:5000` on the Docker `kind` network. Kubernetes therefore sees the same image reference the host publishes while containerd reaches the registry over the internal Docker network.

`manage-local-registry.sh` and `manage-simplematch-live.sh` do not trust a same-named Docker container by name alone. Existing registry state is accepted only when the configured image, restart policy, named data volume, and host-published endpoint match the repository contract. A conflicting same-named container is a hard error rather than an object that setup silently reuses.

Create or inspect the registry with:

```text
bash scripts/manage-local-registry.sh create
bash scripts/manage-local-registry.sh connect
bash scripts/manage-local-registry.sh verify
```

`manage-simplematch-live.sh create` also ensures the registry exists, connects the canonical cluster, verifies the integration, and establishes a clean resource baseline. Cluster deletion preserves registry data unless the registry itself is explicitly purged.

The previous direct `kind load docker-image` path has been removed. `normalize-local-images-for-kind.sh` and its normalization Dockerfile no longer exist, and preparation/rendering no longer expose a transport selector. A stale `SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT` override is rejected fail-closed.

## Image-path responsibilities

```text
scripts/lib/local-image-inventory.sh
    canonical service/build/repository identity

scripts/lib/local-image-transport.sh
    side-effect-free digest-lock semantics, rendering profiles,
    canonical local-registry identity, and rejection of removed overrides

scripts/prepare-local-kubernetes-images.sh
    verify registry/kind integration and invoke publication

scripts/publish-local-images.sh
    push canonical images and atomically publish the digest lockfile

scripts/render-local-kubernetes-manifest.sh
    fail-closed digest substitution and atomic manifest output

scripts/run-local-production-like-certification.sh
    shared configuration/state plus certification module composition
```

No component parses another component's human-readable output to discover image identity. The canonical inventory is the source of service/build/repository identity; the lockfile is the machine-readable hand-off from publication to rendering.

## Certification runner modularity

The production-like runner is intentionally split by responsibility:

```text
local-certification-framework.sh
    CLI help, evidence/report lifecycle, phase execution,
    resume markers, cleanup, deadlines

local-certification-kafka.sh
    Compose readiness, Kafka bootstrap, fixtures,
    producer/capacity evidence, Kafka failure checks

local-certification-kubernetes.sh
    digest-rendered manifest generation/splitting,
    Kubernetes inputs, platform/migration helpers

local-certification-connect.sh
    Kafka Connect registration, REST/status evidence

local-certification-workloads.sh
    Matching-only selection, workload readiness/verification

local-certification-bootstrap.sh
    argument/config validation, run context, prerequisites,
    namespace/evidence initialization, source signature

local-certification-run.sh
    final cross-domain phase ordering
```

The modules are sourced implementation seams, not independent public entry points. Shared run state remains owned by `run-local-production-like-certification.sh`. The resume source signature includes these modules so a behavior change invalidates reusable phase evidence. Registry preparation is deliberately refreshable during resume because a phase marker cannot prove that mutable external registry/cache state still exists.

## Registry certification flow

```text
build-local-images.sh
-> prepare-local-kubernetes-images.sh
-> verify canonical local registry / kind integration
-> publish-local-images.sh
-> immutable digest lockfile
-> render-local-kubernetes-manifest.sh
-> digest-pinned Kubernetes manifest
-> scheduler places a Pod
-> only the scheduled node pulls the required image
```

Run the normal certification with:

```text
bash scripts/run-local-production-like-certification.sh
```

`--matching-fleet-only` publishes only the Matching application image and intentionally produces a partial result. A full certification publishes the canonical local application-image inventory.

## Image lockfile

The lockfile format is:

```text
service|source-image|registry-tag|registry-digest-reference
```

`local-image-transport.sh` validates it semantically:

- every service must exist in the canonical inventory;
- the source repository must be the canonical repository for that service;
- services and source repositories must be unique;
- source and registry tags must agree;
- registry tag and digest reference must identify the same repository;
- the registry repository must equal `<simplematch_registry_endpoint>/<canonical-source-repository>` exactly;
- runtime references must end in a canonical lowercase `sha256` digest.

The exact endpoint rule matters because the lockfile can also be supplied to the renderer. A syntactically valid `remote.example/...@sha256:...` reference is not accepted as local certification identity merely because its repository suffix matches the canonical source repository.

Publication writes a temporary lockfile and atomically replaces the active lock only after every selected push and complete validation succeed. An interrupted publish therefore cannot leave a half-written lock that a later certification run mistakes for valid state. Transient host-side `localhost:5001/...:<tag>` aliases are removed after publication; registry content and immutable digest identity remain.

## Digest-based manifest rendering

```text
bash scripts/render-local-kubernetes-manifest.sh \
  --image-lock out/local-images.lock \
  --namespace simplematch-local \
  --output out/local-kubernetes.yaml
```

The renderer creates a temporary outer Kustomization and leaves `deploy/k8s/overlays/local` registry-agnostic. It accepts exactly two repository-owned lock profiles:

```text
full
matching-only
```

`full` requires every application image represented by the local overlay. A complete publication may also contain canonical verification-only images not present in that overlay. `matching-only` must contain exactly the Matching image. Any other partial lock is rejected instead of producing a mixed mutable/digest deployment.

The rendering contract checks local-overlay inventory parity and verifies each selected digest after Kustomize. A full render rejects any remaining mutable `:local` application image. When `--output` is supplied, all semantic checks run against generated temporary state before an atomic rename replaces the previous output.

Matching uses the same registry manifest digest for the runtime image, `matching-session-config`, and Open Barrier identity. `verify-matching-fleet-live.sh` also requires digest-pinned images; the obsolete local-image compatibility identity has been removed. `--allow-shared-node` remains only as a local topology relaxation for fifteen logical owners.

## Kubelet image-cache garbage collection

`deploy/kind/simplematch-live.yaml` defines:

```text
imageMinimumGCAge: 10m0s
imageMaximumGCAge: 24h0m0s
imageGCHighThresholdPercent: 80
imageGCLowThresholdPercent: 70
```

The minimum age protects newly pulled images from immediate disk-pressure collection. The maximum tracked unused age provides background cache aging even without reaching the high threshold; the 80/70 thresholds provide hysteresis. Kubelet image-age tracking resets when kubelet restarts, so 24 hours is not a persistent wall-clock deletion deadline.

`manage-simplematch-live.sh create` and `verify` read `/var/lib/kubelet/config.yaml` from all four kind containers and fail if effective policy drifts from repository policy.

## Runtime cleanup

Every runner-owned disposable namespace must carry:

```text
simplematch.io/lifecycle=disposable
simplematch.io/managed-by=<runner>
simplematch.io/run-id=<id>
```

The lifecycle label is the only automatic namespace-deletion authority. Routine cleanup never infers ownership from a namespace prefix. Historical unlabeled namespaces must be inspected and explicitly labelled before routine deletion.

Routine cleanup order is:

```text
delete labelled disposable namespace
-> wait for namespace deletion
-> wait for PV claim references to disappear
-> allow kubelet/containerd to release runtime references
-> crictl rmi --prune on canonical kind nodes
-> compare current resource state with the clean baseline
```

Use:

```text
bash scripts/simplematch-clean-local-disk.sh --report-details
```

The default routine path is project-scoped. It does not guess ownership of `pack-cache-*` volumes and does not run daemon-wide Docker builder/image/volume prune. `--aggressive` is the explicit opt-in for globally unused Docker resources and builder caches and can affect unrelated projects. Routine cleanup never manually removes containerd snapshot files.

## Resource baseline and growth

`manage-simplematch-live.sh create` records a clean baseline only after topology, registry, StorageClass, and executable PV-affinity verification complete and the storage probe has been removed. The default file is:

```text
out/local-resource-baseline.json
```

A baseline is accepted only when the cluster has no disposable namespaces, no non-baseline Pods, and no PVs. It records Docker system usage, optional registry-volume size, each kind node's containerd/content/overlayfs bytes, exited-container and NotReady-sandbox counts, disposable namespaces, non-baseline Pods, and PV count.

Each baseline is tied to the exact kind cluster generation using the Docker container IDs of kind nodes. Snapshot validation is fail-closed: node names/container IDs must be unique, measurements must be non-negative integers, and aggregate totals must equal the per-node sums.

Resource collection is non-atomic because kubelet/containerd can mutate while filesystem measurements run. `local-resource-report.sh` therefore owns bounded whole-snapshot retry. `manage-simplematch-live.sh create` uses the same report path when establishing its baseline rather than bypassing the retry policy with a one-shot low-level collection. The default attempt count is three and can be changed with `SIMPLEMATCH_LOCAL_RESOURCE_SNAPSHOT_ATTEMPTS`; exhaustion is a hard failure.

Resource comparison reports:

```text
NO_CONTAINERD_GROWTH
ACTIVE_WORKLOAD_GROWTH
IDLE_RESIDUAL_GROWTH
```

`ACTIVE_WORKLOAD_GROWTH` means workloads/PV state still exist, so growth may be legitimate runtime state. `IDLE_RESIDUAL_GROWTH` means the cluster returned to its baseline Kubernetes workload shape but containerd remains larger. In that state `recycle_candidate=true`; the report recommends but never automatically performs cluster recycle. Registry growth is reported separately because cluster deletion does not delete registry data.

## Hard reset

```text
bash scripts/hard-reset-local.sh
```

Default hard reset is still destructive, but its ownership scope is explicit:

- the canonical `simplematch-live` cluster;
- the canonical production-like Compose project;
- additional `simplematch*` clusters/projects only when explicitly passed with `--kind-cluster` / `--compose-project`;
- the repository-owned local registry;
- unreferenced SimpleMatch-tagged host images;
- generated repository build/evidence state.

Before deleting anything, hard reset discovers SimpleMatch kind/Compose ownership from Docker labels. If another SimpleMatch runtime exists outside the selected set, reset stops fail-closed because removing shared registry/build state could damage that runtime. The user must either leave that runtime alone and avoid hard reset, or explicitly add it to the deletion scope.

Canonical cluster deletion is delegated to `manage-simplematch-live.sh delete` as a required safety gate. If the manager cannot prove exact cluster identity, hard reset stops; generic orphan-container cleanup is not allowed to bypass that refusal. Residual cleanup only matches the selected cluster/project sets.

Daemon-wide Pack/BuildKit caches and unrelated resources are preserved by default. `--aggressive-unused-docker` explicitly opts into global unused-container/image/volume/network and builder-cache pruning and may affect other projects.

## Validation

`Local Resource Lifecycle CI` deterministic contracts run:

```text
test-local-registry-resource-lifecycle.sh
test-local-image-transport.sh
test-local-image-rendering.sh
test-local-production-like.sh
test-local-resource-report.sh
test-local-resilience.sh
test-simplematch-kind-manager.sh
```

They verify registry container identity, exact local-registry lock identity, registry-only interfaces, full/Matching-only rendering, atomic output, certification module boundaries/resume signatures, namespace/PV cleanup ownership, hard-reset scope, bounded baseline collection, resource invariants/growth classification, resilience behavior, and kubelet image-GC policy.

The live kind job creates the actual one-control-plane/three-worker cluster, connects and verifies the repository registry, runs the PV-affinity probe, establishes a clean baseline, publishes a canonical Matching image through the real publisher, consumes its generated lock through the real renderer, schedules one digest-backed Pod to worker slot 0, proves that only that worker acquires the application repository, and tears down its disposable resources.

CDC CI remains a regression boundary for the certification-module split: Matching topic cutover resolves Kafka bootstrap from `local-certification-kafka.sh`, while Kafka durability and live CDC checks remain unchanged.

## Removed compatibility surface

Registry publication plus digest-pinned rendering is the only supported local Kubernetes application-image delivery model. Old invocations using `--image-transport`, renderer/preparation `--transport`, `SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT`, `kind load docker-image`, `normalize-local-images-for-kind.sh`, or verifier `--allow-local-image` must be removed rather than preserved behind another abstraction layer.

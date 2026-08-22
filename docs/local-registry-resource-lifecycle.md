# Local registry and resource lifecycle

The reusable `simplematch-live` kind cluster must not treat application images as permanent node-local state. Each kind node has its own containerd content store and unpacked overlayfs snapshots, so preloading the complete SimpleMatch image inventory into every node multiplies disk usage even when a workload never runs there. The repository therefore treats node-local images as reproducible cache: application images are published to one local OCI registry, Kubernetes manifests are pinned by digest, and only nodes that actually schedule a workload pull the required image.

## Registry-only image transport

Local Kubernetes application images use the repository-owned registry exclusively:

- host endpoint: `localhost:5001`
- container: `simplematch-local-registry`
- image: `registry:3`
- data volume: `simplematch-local-registry-data`

The host pushes `localhost:5001/...`. Each kind node has `/etc/containerd/certs.d/localhost:5001/hosts.toml` mapping that logical endpoint to `http://simplematch-local-registry:5000` on the Docker `kind` network. Kubernetes therefore uses the same logical image reference that the host publishes while containerd reaches the registry over the internal Docker network.

Create or inspect the registry with:

```text
bash scripts/manage-local-registry.sh create
bash scripts/manage-local-registry.sh connect
bash scripts/manage-local-registry.sh verify
```

`manage-simplematch-live.sh create` also ensures the registry exists, connects the canonical cluster, verifies the integration, and establishes a clean resource baseline. Deleting the cluster preserves registry data unless the registry itself is explicitly purged.

The previous direct `kind load docker-image` path has been removed. `normalize-local-images-for-kind.sh` and its normalization Dockerfile no longer exist, and `prepare-local-kubernetes-images.sh` plus `render-local-kubernetes-manifest.sh` no longer expose `--transport`. A stale `SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT` environment override is rejected fail-closed instead of silently selecting an obsolete delivery path.

## Image-path responsibilities

The image path is split by responsibility rather than by transport mode:

```text
scripts/lib/local-image-inventory.sh
    canonical service/build/repository identity

scripts/lib/local-image-transport.sh
    side-effect-free digest-lock semantics, rendering profiles,
    and rejection of removed transport overrides

scripts/prepare-local-kubernetes-images.sh
    verify registry integration and invoke publication

scripts/publish-local-images.sh
    push canonical images and atomically publish the digest lockfile

scripts/render-local-kubernetes-manifest.sh
    fail-closed digest substitution and atomic manifest output

scripts/run-local-production-like-certification.sh
    configuration/state plus module composition only
```

No component parses another component's human-readable output to discover image identity. The canonical inventory is the single source for service, build source, and repository identity; the lockfile is the single hand-off between publication and rendering.

## Certification runner modularity

`run-local-production-like-certification.sh` deliberately owns only shared configuration, run state, module loading, and the top-level lifecycle boundary. Domain behavior is sourced from cohesive modules:

```text
scripts/lib/local-certification-framework.sh
    CLI help, evidence/report lifecycle, phase execution, resume markers,
    cleanup, deadline handling, and common certification control flow

scripts/lib/local-certification-kafka.sh
    Compose readiness, Kafka topic/bootstrap helpers, fixtures,
    producer/capacity evidence, and Kafka failure checks

scripts/lib/local-certification-kubernetes.sh
    digest-rendered manifest generation, manifest splitting,
    Kubernetes inputs, migration/platform deployment helpers

scripts/lib/local-certification-connect.sh
    Kafka Connect registration, REST interaction, status collection,
    and connector-specific evidence

scripts/lib/local-certification-workloads.sh
    Matching-only selection, workload readiness, and workload verification

scripts/lib/local-certification-bootstrap.sh
    CLI parsing, configuration validation, run context, prerequisites,
    namespace/evidence initialization, and source signature setup

scripts/lib/local-certification-run.sh
    final phase ordering across Compose, Kafka, image publication,
    Kubernetes deployment, connector setup, and verification
```

The modules are not independent executables. They are sourced by the top-level runner and operate on run state owned there. This keeps orchestration explicit while preventing the runner from becoming a thousand-line cross-domain script.

Resume remains fail-closed around code changes: the source signature includes the certification modules, so changing module behavior invalidates reusable phase evidence. Registry preparation is also deliberately refreshable during `--resume`; a phase marker cannot prove that mutable external registry/cache state still exists after the registry was purged or kubelet garbage collection reclaimed node images.

## Registry certification flow

For a normal certification run, the Kubernetes application-image path is:

```text
build-local-images.sh
-> prepare-local-kubernetes-images.sh
-> verify local registry / kind integration
-> publish-local-images.sh
-> immutable digest lockfile
-> render-local-kubernetes-manifest.sh
-> digest-pinned Kubernetes manifest
-> scheduler places a Pod
-> only the scheduled node pulls the required image
```

Run the full certification with:

```text
bash scripts/run-local-production-like-certification.sh
```

For `--matching-fleet-only`, only the Matching application image is published and the report is intentionally partial. A full certification publishes the canonical local application image inventory.

## Image lockfile

Build remains separate from publication:

```text
bash scripts/build-local-images.sh --tag local
```

Publish the resulting host images with:

```text
bash scripts/publish-local-images.sh \
  --tag local \
  --output out/local-images.lock
```

The lockfile format is:

```text
service|source-image|registry-tag|registry-digest-reference
```

`local-image-transport.sh` validates the lock semantically rather than merely checking delimiters:

- every service must exist in the canonical inventory;
- the source repository must be the canonical repository for that service;
- services and source repositories must be unique;
- source and registry tags must agree;
- registry tag and digest reference must identify the same repository;
- the registry repository must preserve the canonical source repository identity;
- runtime references must end in a canonical lowercase `sha256` digest.

Publication writes a temporary lockfile and atomically replaces the active lockfile only after every selected push and the complete lock validation succeed. An interrupted publish therefore cannot leave a half-written lock that a later certification run mistakes for valid state. Transient host-side `localhost:5001/...:<tag>` aliases are removed after publication; the registry content and immutable digest reference remain.

## Digest-based manifest rendering

Render a local manifest with:

```text
bash scripts/render-local-kubernetes-manifest.sh \
  --image-lock out/local-images.lock \
  --namespace simplematch-local \
  --output out/local-kubernetes.yaml
```

The renderer creates a temporary outer Kustomization, leaving `deploy/k8s/overlays/local` registry-agnostic. It accepts only two repository-owned lock profiles:

```text
full
matching-only
```

`full` requires every application image represented by the local overlay. A complete publication may include canonical verification-only images that are not present in the overlay; those entries are ignored by rendering. `matching-only` must contain exactly the Matching image and exists for the Matching fleet-only certification path. Any other partial lock is rejected instead of silently producing a mixed mutable/digest deployment.

The rendering contract checks that the canonical local-overlay repository set matches the tracked `images:` entries in `deploy/k8s/overlays/local/kustomization.yaml`. After Kustomize renders, every selected service is checked again against the expected registry digest reference. A full render also rejects any remaining mutable `:local` application image.

When `--output` is supplied, Kustomize renders into generated temporary state and all post-render checks run there first. Only a fully validated manifest is copied to a temporary file in the destination directory and atomically renamed over the requested output. A failed Kustomize invocation or failed semantic check therefore preserves the previous valid output.

Matching uses the same registry manifest digest for the rendered runtime image, `matching-session-config`, and Open Barrier identity. There is no separate Docker image-ID compatibility identity after the registry-only cutover.

## Kubelet image cache garbage collection

The registry/digest path makes node-local application images reproducible cache rather than durable cluster state. `deploy/kind/simplematch-live.yaml` therefore defines a local-lab-specific kubelet image-GC policy:

```text
imageMinimumGCAge: 10m0s
imageMaximumGCAge: 24h0m0s
imageGCHighThresholdPercent: 80
imageGCLowThresholdPercent: 70
```

`imageMinimumGCAge` protects images that were just pulled from immediate disk-pressure collection. `imageMaximumGCAge` allows long-unused images to become eligible for collection even if disk usage never crosses the high threshold. The 80/70 thresholds provide hysteresis so image GC does not oscillate around one disk-usage value.

This policy belongs to the canonical kind configuration, not to certification or cleanup scripts. kind/kubeadm propagates the `KubeletConfiguration` from the control-plane configuration to all nodes. `manage-simplematch-live.sh create` and `verify` read `/var/lib/kubelet/config.yaml` from all four kind containers and fail if the effective image-GC policy drifts from repository policy.

Kubelet image-age tracking is not persistent across kubelet restarts, so `imageMaximumGCAge: 24h0m0s` is a maximum tracked unused age rather than a persistent wall-clock deletion deadline. Kubelet GC complements explicit cleanup; neither path manually deletes containerd snapshot files.

## Runtime cleanup

Every runner-owned disposable Kubernetes namespace must carry:

```text
simplematch.io/lifecycle=disposable
simplematch.io/managed-by=<runner>
simplematch.io/run-id=<id>
```

`simplematch.io/lifecycle=disposable` is the only automatic namespace-deletion authority. Routine cleanup never infers ownership from a namespace prefix. If label establishment fails during namespace creation, the namespace is treated as failed setup and removed rather than leaving an ambiguously owned resource behind. Resume verifies the expected ownership before reusing an existing certification namespace.

Historical namespaces created before this contract may be unlabeled. Routine cleanup intentionally leaves them alone. If an old namespace is known to be disposable, migrate it explicitly:

```text
kubectl --context kind-simplematch-live label namespace <namespace> \
  simplematch.io/lifecycle=disposable \
  simplematch.io/managed-by=manual-migration \
  simplematch.io/run-id=legacy
```

The routine cleanup order is intentional:

```text
delete labeled disposable namespace
-> wait until namespace deletion completes
-> wait until PV claim references disappear
-> allow kubelet/containerd to release runtime references
-> crictl rmi --prune on kind nodes
-> compare current resource state with the clean baseline
```

A namespace or PV cleanup failure stops cleanup before CRI image pruning. Exited containers, Pod sandboxes, snapshots, and image content are reference-managed runtime state, so deleting low-level containerd files directly is not a safe ownership model.

Use:

```text
bash scripts/simplematch-clean-local-disk.sh --report-details
```

for routine cleanup while preserving the reusable cluster and registry cache. Do not manually delete `/var/lib/containerd` files or use `ctr snapshots rm` as a normal cleanup mechanism.

## Resource baseline and growth

`manage-simplematch-live.sh create` records a clean baseline after topology, registry, StorageClass, and executable PV-affinity verification have completed and the storage probe has been synchronously removed. The default file is:

```text
out/local-resource-baseline.json
```

Override it with `SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_FILE`. A baseline is accepted only when the cluster has no disposable namespaces, no Pods outside the baseline system namespaces, and no PVs. This prevents an application run from accidentally becoming the definition of clean state.

The baseline records:

- Docker `system df` rows for host images, containers, volumes, and build cache;
- local-registry data-volume size when the host filesystem permits non-interactive measurement;
- each kind node's total `/var/lib/containerd` bytes;
- each node's containerd content-store and overlayfs-snapshot bytes;
- exited-container and NotReady-sandbox counts;
- Kubernetes disposable namespaces, non-baseline Pods, and PV count.

Each baseline is tied to the exact kind cluster generation using Docker container IDs for the kind nodes. A baseline from a deleted/recreated cluster is rejected even if the new cluster reuses the same cluster and node names. `manage-simplematch-live.sh delete` may leave the old file as forensic evidence; the next successful create establishes the new generation's baseline.

Snapshot validation is fail-closed. Node names and Docker container IDs must be unique, byte/count measurements must be non-negative integers, and every `kind.totals.*` value must equal the sum of the corresponding per-node measurements. A malformed or internally inconsistent snapshot is never used for a growth decision.

Resource collection is necessarily non-atomic because kubelet and containerd can mutate runtime state during measurement. `local-resource-report.sh` retries collection as a whole for bounded transient Docker/kubelet/containerd measurement races rather than accepting a partial low-level snapshot. The default retry count is three and can be overridden with `SIMPLEMATCH_LOCAL_RESOURCE_SNAPSHOT_ATTEMPTS`; exhausting the attempts remains a hard failure.

Use the read-only report at any time:

```text
bash scripts/local-resource-report.sh
```

Save a standalone snapshot with:

```text
bash scripts/local-resource-report.sh --output out/local-resource-now.json
```

Establish a clean baseline explicitly with:

```text
bash scripts/local-resource-report.sh \
  --write-baseline out/local-resource-baseline.json
```

The comparison deliberately avoids an arbitrary fixed "N GB means rebuild" threshold. It reports one of:

```text
NO_CONTAINERD_GROWTH
ACTIVE_WORKLOAD_GROWTH
IDLE_RESIDUAL_GROWTH
```

`ACTIVE_WORKLOAD_GROWTH` means containerd has grown while non-baseline workloads or PV state still exist, so the growth may be legitimate runtime state. `IDLE_RESIDUAL_GROWTH` requires the cluster to be back at its baseline Kubernetes workload shape while containerd remains larger than the clean baseline. In that state `recycle_candidate=true`: rebuilding the reusable kind cluster is a defensible reclamation action, but the report never destroys the cluster automatically.

Large and roughly equal content/snapshot growth across all nodes usually indicates duplicated cache state rather than PVC application data. Registry-only transport reduces unnecessary duplication because an application repository is not imported into nodes that never schedule it, but each node that actually executes an image still keeps its own containerd content and unpacked layers. Registry growth is reported separately because deleting the kind cluster does not delete registry data.

## Hard reset

When node-local containerd state is no longer worth preserving, rebuild the cluster rather than manually mutating snapshots:

```text
bash scripts/hard-reset-local.sh
```

Hard reset delegates canonical cluster deletion to `manage-simplematch-live.sh`, purges registry cache by default, removes repository-owned SimpleMatch host images, and clears generated local state. It does not use Compose `--rmi all`, because PostgreSQL, Kafka, Redis, Debezium, and other upstream images are not repository-owned artifacts. `--aggressive-unused-docker` remains explicitly daemon-wide and can affect unrelated projects.

## Validation

`Local Resource Lifecycle CI` has two layers. The deterministic contract job runs:

```text
test-local-registry-resource-lifecycle.sh
test-local-image-transport.sh
test-local-image-rendering.sh
test-local-production-like.sh
test-local-resource-report.sh
test-local-resilience.sh
test-simplematch-kind-manager.sh
```

Together these contracts verify:

- registry-only public interfaces and fail-closed rejection of removed transport selectors;
- canonical image inventory and semantic digest-lock identity;
- full and Matching-only rendering profiles, local-overlay inventory parity, digest substitution, unsupported-partial-lock rejection, and atomic output preservation;
- certification module boundaries, top-level runner size/responsibility, module-aware resume source signatures, and dry-run orchestration;
- namespace ownership, PV-aware cleanup ordering, cluster-generation/node-set checks, resource snapshot invariants, baseline-relative growth classification, and resilience behavior;
- canonical kubelet image-GC values and manager verification behavior.

Changes to the registry/resource lifecycle scripts, certification modules, local Kubernetes/kind configuration, tests, or this design document trigger the workflow.

The live integration job installs pinned kind v0.32.0 and runs `test-local-resource-kind-integration.sh`. It creates the actual one-control-plane/three-worker `simplematch-live` cluster, connects the local registry, runs the PV-affinity probe, establishes a clean baseline, verifies the manager and resource report, publishes a uniquely tagged canonical Matching source image through the real publisher, consumes the generated lockfile through the real renderer, schedules one digest-backed Pod to worker slot 0, and proves that only that worker acquires the application repository. It then deletes the disposable namespace, canonical cluster, registry data, and host smoke image. Cleanup is protected by an EXIT trap.

CDC CI provides an additional regression boundary because the certification refactor moved Kafka topic creation into `local-certification-kafka.sh`. Its Matching topic cutover contract resolves the Kafka module rather than assuming topic bootstrap lives in the top-level runner, while the existing Kafka durability and live CDC checks continue to run unchanged.

## Removed compatibility surface

Registry publication plus digest-pinned rendering is now the only supported local Kubernetes application-image delivery model. Old invocations using `--image-transport`, renderer/preparation `--transport`, `SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT`, `kind load docker-image`, or `normalize-local-images-for-kind.sh` must be removed rather than preserved behind another abstraction layer. This keeps one image identity model throughout publication, certification, Kubernetes scheduling, cache lifecycle, and resource reporting.
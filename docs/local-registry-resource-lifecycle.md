# Local registry and resource lifecycle

The reusable `simplematch-live` kind cluster must not treat application images as permanent node-local state. Each kind node has its own containerd content store and unpacked overlayfs snapshots, so loading the complete SimpleMatch image inventory into every node multiplies local disk usage even when a workload never runs on a given node.

## Image transport

The default local Kubernetes image transport is a repository-owned OCI registry:

- host endpoint: `localhost:5001`
- container: `simplematch-local-registry`
- image: `registry:3`
- data volume: `simplematch-local-registry-data`

The host pushes `localhost:5001/...`. Each kind node has `/etc/containerd/certs.d/localhost:5001/hosts.toml` mapping that logical endpoint to `http://simplematch-local-registry:5000` on the Docker `kind` network. Kubernetes manifests therefore use the same logical `localhost:5001/...` image reference that the host publishes, while only nodes that actually schedule a workload pull that image.

Create or inspect the registry with:

```text
bash scripts/manage-local-registry.sh create
bash scripts/manage-local-registry.sh connect
bash scripts/manage-local-registry.sh verify
```

`manage-simplematch-live.sh create` also ensures this registry exists and connects the newly created canonical cluster. Deleting the cluster preserves registry data so a replacement cluster can pull the same published images.

The image path is split by responsibility:

```text
scripts/lib/local-image-inventory.sh
    canonical service/build/repository inventory

scripts/lib/local-image-transport.sh
    transport policy + digest-lock semantics + rendering profiles

scripts/prepare-local-kubernetes-images.sh
    side effects: registry publish OR legacy kind load

scripts/publish-local-images.sh
    push canonical images + atomically publish digest lockfile

scripts/render-local-kubernetes-manifest.sh
    fail-closed, transport-aware manifest rendering

scripts/run-local-production-like-certification.sh
    orchestration only
```

Build, publication, rendering policy, and the legacy `kind-load` path therefore consume the same canonical image identity instead of parsing each other's CLI output or maintaining separate image lists.

The certification runner defaults to `registry`:

```text
bash scripts/run-local-production-like-certification.sh
```

The previous direct-import behavior remains an explicit compatibility fallback:

```text
bash scripts/run-local-production-like-certification.sh \
  --image-transport kind-load
```

or:

```text
SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT=kind-load \
  bash scripts/run-local-production-like-certification.sh
```

The fallback implementation is isolated in `prepare-local-kubernetes-images.sh`; the certification runner itself does not call `normalize-local-images-for-kind.sh` or `kind load docker-image`.

## Registry-first certification flow

For a normal certification run the Kubernetes image path is:

```text
build-local-images.sh
-> prepare-local-kubernetes-images.sh --transport registry
-> verify local registry / kind integration
-> publish-local-images.sh
-> local image lockfile containing immutable digest references
-> render-local-kubernetes-manifest.sh
-> digest-pinned Kubernetes manifest
-> scheduler places a Pod
-> only that node pulls the required image
```

For `--matching-fleet-only`, only the Matching image is published. A full certification publishes the canonical local application image inventory.

The transport-preparation phase is deliberately refreshable during `--resume`. A successful phase marker is not enough to prove external image state still exists: the local registry might have been purged and node caches might have been garbage-collected. Resume therefore re-verifies and republishes registry state, or reloads images when the compatibility transport is selected.

## Image lockfile

Build remains a separate concern:

```text
bash scripts/build-local-images.sh --tag local
```

Publish the resulting host images with:

```text
bash scripts/publish-local-images.sh --tag local --output out/local-images.lock
```

The lockfile format is:

```text
service|source-image|registry-tag|registry-digest-reference
```

The lockfile is validated centrally by `local-image-transport.sh`. Validation is semantic rather than merely syntactic:

- every service must exist in `local-image-inventory.sh`;
- the source repository must be the canonical repository for that service;
- services and source repositories must be unique;
- source and registry tags must agree;
- registry tag and digest reference must identify the same repository;
- the registry repository must preserve the canonical source repository identity;
- every runtime reference must end in a canonical `sha256` digest.

Publishing writes to a temporary lockfile and atomically replaces the active lockfile only after all selected pushes and validation succeed. An interrupted publish therefore cannot leave a half-written lockfile that later rendering treats as valid. The lockfile itself is replaceable state; the deployment identity inside it is immutable because Kubernetes consumes digest references rather than mutable tags.

## Digest-based manifest rendering

A registry-backed local manifest can be rendered with:

```text
bash scripts/render-local-kubernetes-manifest.sh \
  --transport registry \
  --image-lock out/local-images.lock \
  --namespace simplematch-local \
  --output out/local-kubernetes.yaml
```

The renderer creates a temporary outer Kustomization. The repository-managed `deploy/k8s/overlays/local` files remain registry-agnostic. Registry rendering supports only two repository-owned profiles:

```text
full
matching-only
```

`full` requires the lockfile to contain every service represented by the local overlay's application image set. A normal full publication may also contain canonical verification images that are not part of that overlay; those extra entries are ignored by rendering. `matching-only` is the explicit partial profile used by the existing Matching fleet-only certification and must contain exactly the Matching image. Arbitrary partial lockfiles are rejected instead of silently leaving unrelated workloads on mutable `:local` images.

The rendering contract also checks that the canonical local-overlay repository set matches the tracked `images:` entries in `deploy/k8s/overlays/local/kustomization.yaml`. This makes an overlay image addition or removal an explicit inventory change rather than a hidden rendering drift.

After Kustomize renders the manifest, the renderer verifies every selected service again against the expected registry digest reference. A full registry render additionally rejects any remaining `:local` image. In `kind-load` mode the tracked local image names remain unchanged for compatibility.

When `--output` is used, Kustomize first renders to generated temporary state and all post-render checks run against that temporary manifest. Only a fully validated result is copied to a temporary file in the destination directory and atomically renamed over the requested output. A failed render therefore leaves the previously valid output untouched.

Matching uses the same registry manifest digest for the rendered runtime image, `matching-session-config`, and Open Barrier identity. The compatibility path continues to use the local Docker image ID because that path imports the local image directly into kind.

## Kubelet image cache garbage collection

The registry/digest path makes node-local application images reproducible external cache rather than durable cluster state. The canonical `simplematch-live` kind configuration therefore enables a local-lab-specific kubelet image-GC policy:

```text
imageMinimumGCAge: 10m0s
imageMaximumGCAge: 24h0m0s
imageGCHighThresholdPercent: 80
imageGCLowThresholdPercent: 70
```

`imageMinimumGCAge` protects images that were only just pulled from being immediately selected by disk-pressure garbage collection. `imageMaximumGCAge` gives unused images a 24-hour maximum tracked age, so old application caches can become eligible for collection even when the image filesystem never reaches the high disk threshold. The 80/70 thresholds retain a ten-percentage-point hysteresis window: crossing 80% triggers image garbage collection and kubelet can continue reclaiming until usage returns to the low threshold instead of oscillating around one value.

This policy belongs to `deploy/kind/simplematch-live.yaml`, not to certification or cleanup scripts. kind/kubeadm reads `KubeletConfiguration` from the first/control-plane node and applies that configuration to every node. `manage-simplematch-live.sh create` and `verify` treat the effective policy as a canonical cluster invariant by reading `/var/lib/kubelet/config.yaml` from all four kind node containers and failing if any node differs. This catches both incorrectly created clusters and reusable clusters whose effective kubelet configuration no longer matches repository policy.

Age-based image usage tracking is not persistent across kubelet restarts. Restarting kubelet resets the tracked age, so `imageMaximumGCAge: 24h0m0s` starts a new 24-hour eligibility window after a restart. The age bound is therefore a background cache-lifecycle policy, not a wall-clock deletion guarantee.

Kubelet image GC complements rather than replaces explicit lifecycle cleanup. Routine cleanup still waits for disposable namespace and PV teardown before `crictl rmi --prune`, because that path is an operator-requested immediate reclaim after Kubernetes releases runtime references. Kubelet GC provides automatic long-term pressure/age-based cache control between explicit cleanups. Neither mechanism manually deletes containerd snapshot files.

## Runtime cleanup

Every runner-owned disposable Kubernetes namespace must carry:

```text
simplematch.io/lifecycle=disposable
simplematch.io/managed-by=<runner>
simplematch.io/run-id=<id>
```

`simplematch.io/lifecycle=disposable` is the only automatic namespace-deletion authority. Routine cleanup never infers ownership from a namespace prefix. This prevents a naming convention from becoming an implicit destructive policy and keeps deletion semantics explicit at resource creation time.

The local production-like certification and local resilience runners establish these labels when creating their namespaces. If label establishment fails, namespace creation is treated as failed and the partially created namespace is removed rather than leaving an ambiguously owned run behind. Resume also verifies the expected ownership before reusing a certification namespace.

Historical namespaces created before this contract may be unlabeled. Routine cleanup intentionally leaves them alone. Inspect an old namespace and its resources first; only if it is known to be disposable should it be migrated explicitly, for example:

```text
kubectl --context kind-simplematch-live label namespace <namespace> \
  simplematch.io/lifecycle=disposable \
  simplematch.io/managed-by=manual-migration \
  simplematch.io/run-id=legacy
```

After that explicit ownership decision, routine cleanup may delete it. If the whole local lab is disposable, an explicit cluster rebuild is the simpler alternative.

The cleanup order is intentional:

```text
delete disposable namespace
-> wait until namespace deletion completes
-> wait until PV claim references disappear
-> allow kubelet/containerd to release runtime references
-> crictl rmi --prune on kind nodes
```

A namespace or PV cleanup failure stops the routine cleanup before CRI image pruning. This matters because exited containers, Pod sandboxes, snapshots, and image content are reference-managed runtime state.

Do not manually delete `/var/lib/containerd` files or call `ctr snapshots rm` as a normal cleanup mechanism.

Use:

```text
bash scripts/simplematch-clean-local-disk.sh --report-details
```

for routine cleanup while preserving the reusable cluster and registry cache.

## Resource baseline and growth

`manage-simplematch-live.sh create` records a clean baseline after topology, registry, StorageClass, and executable PV-affinity verification have completed and the storage probe has been synchronously removed. The default file is:

```text
out/local-resource-baseline.json
```

Override it with `SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_FILE`. A baseline is accepted only when the cluster has no `simplematch.io/lifecycle=disposable` namespaces, no Pods outside `kube-system` and `local-path-storage`, and no PVs. This prevents an application run from accidentally becoming the definition of "clean".

The baseline records:

- Docker `system df` rows for host images, containers, volumes, and build cache;
- local-registry data-volume size when the host filesystem permits non-interactive measurement;
- each kind node's total `/var/lib/containerd` bytes;
- each node's containerd content-store and overlayfs-snapshot bytes;
- exited-container and NotReady-sandbox counts;
- Kubernetes disposable namespaces, non-baseline Pods, and PV count.

Each baseline is tied to one exact kind cluster generation. The fingerprint is calculated from the Docker container IDs of the kind nodes. A baseline from a deleted/recreated cluster is rejected even if the new cluster has the same name and node names. `manage-simplematch-live.sh delete` leaves the old file only as forensic evidence; the next successful `create` replaces it with the new generation's baseline.

Snapshot validation is fail-closed. Node names and Docker container IDs must be unique, byte/count measurements must be non-negative integers, and every `kind.totals.*` value must equal the sum of the corresponding per-node measurements. A malformed or internally inconsistent baseline/current snapshot is rejected instead of being used for growth decisions.

Resource collection is necessarily non-atomic because containerd and kubelet can mutate runtime state while filesystem measurements are being taken. `local-resource-report.sh` therefore retries collection as a whole for transient Docker/kubelet/containerd races instead of accepting a partial low-level measurement. The default is three bounded attempts and can be overridden with `SIMPLEMATCH_LOCAL_RESOURCE_SNAPSHOT_ATTEMPTS`. Exhausting the attempts remains a hard failure.

Use the read-only report at any time:

```text
bash scripts/local-resource-report.sh
```

When the default baseline exists, the report automatically prints current values and deltas relative to that baseline. A standalone snapshot can be saved with:

```text
bash scripts/local-resource-report.sh --output out/local-resource-now.json
```

A clean baseline can also be established explicitly:

```text
bash scripts/local-resource-report.sh --write-baseline out/local-resource-baseline.json
```

The comparison deliberately has no fixed "N GB means rebuild" threshold. It reports one of three states:

```text
NO_CONTAINERD_GROWTH
ACTIVE_WORKLOAD_GROWTH
IDLE_RESIDUAL_GROWTH
```

`ACTIVE_WORKLOAD_GROWTH` means containerd has grown while non-baseline workloads or PV state still exist, so the growth may be legitimate runtime state. `IDLE_RESIDUAL_GROWTH` requires no disposable namespaces, no non-baseline Pods, and no PVs; it means the cluster is back to its baseline Kubernetes workload shape but containerd remains larger than the clean baseline. In that state `recycle_candidate=true`: rebuilding the reusable kind cluster is a defensible way to reclaim node-local cache/snapshot residue, but the script does not delete the cluster automatically. This avoids inventing an arbitrary disk threshold while still making the decision evidence-based.

Large and roughly equal content/snapshot growth across all nodes usually indicates duplicated image/cache state rather than PVC application data. Registry transport reduces unnecessary growth by avoiding all-node preloading, but any node that actually executes an image still maintains its own containerd cache and unpacked layers.

Registry growth is reported separately because deleting/rebuilding the kind cluster does not remove the registry cache.

## Hard reset

When node-local containerd state is no longer worth preserving, delete the cluster rather than manually mutating snapshots:

```text
bash scripts/hard-reset-local.sh
```

Hard reset delegates canonical cluster deletion to `manage-simplematch-live.sh`, removes the registry cache by default, and removes SimpleMatch host images and generated state. It no longer uses Compose `--rmi all`, because PostgreSQL, Kafka, Redis, Debezium, and other upstream images are not repository-owned images.

`--aggressive-unused-docker` remains explicitly daemon-wide and can affect unrelated projects.

## Validation

`Local Resource Lifecycle CI` has two layers. The deterministic contract job runs:

```text
test-local-registry-resource-lifecycle.sh
test-local-image-transport.sh
test-local-image-rendering.sh
test-local-resource-report.sh
test-local-resilience.sh
test-simplematch-kind-manager.sh
```

The image contracts validate canonical inventory consistency, semantic lock identity, full/matching-only rendering profiles, local-overlay inventory parity, complete digest substitution, rejection of unsupported partial locks, the legacy `kind-load` boundary, and atomic output preservation when Kustomize fails. The lifecycle/resource contracts cover ownership, snapshot invariants/comparison, resilience behavior, and the canonical kind manager. The kind-manager contract additionally parses the embedded `KubeletConfiguration` patch and fixes the local image-GC policy at 10m minimum age, 24h maximum age, and 80/70 disk thresholds. Changes under `deploy/k8s/**` or the canonical kind configuration trigger this workflow.

The second job is a live integration smoke test. It installs a pinned kind release, creates the actual canonical one-control-plane/three-worker `simplematch-live` cluster, connects the local registry, executes the PV-affinity probe, establishes a clean baseline, runs manager verification and a baseline-aware resource report, and then tests demand-driven image delivery. Manager verification reads the effective kubelet configuration from every node before the smoke continues, so CI proves that the repository-owned image-GC policy reached all four kubelets. The smoke then publishes a uniquely tagged canonical Matching source image through `publish-local-images.sh`, consumes the generated digest lockfile through `render-local-kubernetes-manifest.sh`, schedules exactly one digest-backed Pod to worker slot 0, verifies that worker pulled the image, and verifies that the control-plane and the other two workers did not acquire that repository. It then deletes the disposable test namespace, cluster, registry data, and host-side smoke tag. Cleanup is protected by an EXIT trap.

This live test has also exposed runtime-only reliability defects that deterministic fixtures cannot reproduce. Resource snapshot collection therefore treats transient filesystem-measurement races as retryable at the whole-snapshot boundary while keeping malformed or repeatedly failing collection fail-closed.

## Compatibility boundary

The registry path is the default certification transport. `kind-load` remains only as an explicit migration/debugging fallback. Keeping that fallback behind the same transport interface lets it be removed later without changing certification orchestration, digest-based registry identity, resource cleanup, kubelet image-cache policy, or resource reporting.

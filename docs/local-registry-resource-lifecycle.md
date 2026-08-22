# Local registry and resource lifecycle

The reusable `simplematch-live` kind cluster treats application images as reproducible cache, not permanent node-local state. Each kind node owns an independent containerd content store and overlayfs snapshot store, so preloading the complete SimpleMatch image inventory into every node multiplies disk usage even when a workload never runs there.

This document describes the staged local-lab design implemented by the repository. It is bounded development evidence, not a production HA or storage-HA claim.

## 1. Cleanup ownership and ordering

Every certification or resilience namespace that routine cleanup may remove must carry:

```text
simplematch.io/lifecycle=disposable
simplematch.io/managed-by=<runner>
simplematch.io/run-id=<id>
```

`simplematch.io/lifecycle=disposable` is the only automatic namespace-deletion authority. Namespace prefixes are not ownership evidence. Historical unlabeled namespaces remain untouched unless a human explicitly labels them or rebuilds the whole local lab.

Routine cleanup is intentionally ordered:

```text
delete lifecycle-labeled namespaces
-> wait for namespace deletion
-> successfully observe that their PV claim references are gone
-> crictl rmi --prune on kind nodes
-> compare resource state with the clean baseline
```

Kubernetes observation failure is not interpreted as resource absence. If namespace or PV state cannot be confirmed, cleanup fails before CRI image pruning. Normal cleanup never deletes containerd snapshot files directly.

## 2. Read-only resource baseline

`scripts/local-resource-report.sh` collects read-only evidence for the host and each kind node. The snapshot includes:

- Docker `system df` rows, covering host images, containers, volumes, and build cache;
- the local-registry data-volume size when the Docker storage backend exposes a measurable host path;
- each kind node's total `/var/lib/containerd` size;
- each node's `io.containerd.content.v1.content` size;
- each node's overlayfs snapshot-store size;
- Exited CRI container count;
- NotReady CRI sandbox count;
- disposable namespaces, non-baseline Pods, and PV count.

`manage-simplematch-live.sh create` waits for a clean cluster after its storage probe and writes the baseline. A baseline is accepted only when there are no disposable namespaces, no non-baseline Pods, and no PVs. It is tied to the exact kind generation using the Docker container IDs of the kind nodes, so a baseline from a deleted/recreated cluster is not reusable merely because the node names match.

Comparison is baseline-relative rather than threshold-based:

```text
NO_CONTAINERD_GROWTH
ACTIVE_WORKLOAD_GROWTH
IDLE_RESIDUAL_GROWTH
```

`IDLE_RESIDUAL_GROWTH` makes cluster recycle a measurable candidate; it does not automatically delete the cluster. No fixed GB threshold decides recycle.

## 3. Staged local image transport

The public transport contract is:

```text
registry   # default
kind-load  # explicit compatibility fallback
```

The default registry is repository-owned and local-only:

- logical host: `localhost` (not configurable to a remote hostname);
- port: `5001` by default and locally configurable;
- container: `simplematch-local-registry`;
- image: `registry:3`;
- restart policy: `unless-stopped`;
- data volume: `simplematch-local-registry-data`;
- host publication: `127.0.0.1:<port> -> container :5000`;
- Docker network: `kind` by default.

Each kind node receives `/etc/containerd/certs.d/localhost:<port>/hosts.toml`, which maps the logical image endpoint to `http://simplematch-local-registry:5000` on the Docker `kind` network. The host can therefore push to `localhost:<port>`, Kubernetes can keep the same image reference, and only a node that schedules a workload needs to pull that image.

A same-named registry container is reused only after verifying its configured image, restart policy, data volume, and published port. The logical host is deliberately fixed to `localhost`; allowing a remote host would make a workflow intended for local-only publication capable of crossing that ownership boundary.

The fallback remains available through:

```text
bash scripts/run-local-production-like-certification.sh \
  --image-transport kind-load
```

or:

```text
SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT=kind-load \
  bash scripts/run-local-production-like-certification.sh
```

The top-level certification runner does not implement registry publication or direct kind import itself. It selects the transport and delegates to the image-preparation boundary.

## 4. Publication and digest lock

Image responsibility is split by concern:

```text
scripts/lib/local-image-inventory.sh
    canonical service/build/repository identity

scripts/lib/local-image-transport.sh
    transport policy, digest-lock semantics, rendering profiles

scripts/prepare-local-kubernetes-images.sh
    registry publication OR legacy kind-load preparation

scripts/publish-local-images.sh
    tag/push canonical images and atomically publish a digest lock

scripts/normalize-local-images-for-kind.sh
    legacy kind-load transfer normalization only

scripts/render-local-kubernetes-manifest.sh
    transport-aware Kustomize rendering
```

`build-local-images.sh --list` and the publisher consume the same canonical image inventory rather than maintaining duplicate service lists or parsing one another's human-readable output.

Registry preparation never executes `normalize-local-images-for-kind.sh`. The normalizer and `Dockerfile.kind-normalized` exist only for the explicit legacy `kind-load` path.

The lockfile format is:

```text
service|source-image|registry-tag|registry-digest-reference
```

Publication tags each selected local image under `localhost:<port>/<repository>:<tag>`, pushes it, captures the reported OCI manifest digest, validates the complete lock, and atomically replaces the output lockfile. Temporary host-side registry tags are removed after publication; the registry content remains addressable by digest.

Lock validation binds every entry to the canonical inventory: service identity, source repository, source/registry tag agreement, registry tag/digest repository agreement, configured local registry endpoint, uniqueness, and lowercase `sha256` digest shape are checked before use.

## 5. Digest-based Kustomize rendering

Registry rendering uses a transient outer Kustomization rather than modifying the tracked local overlay. It injects `images:` transformations that replace local application image names with exact registry digest references:

```text
localhost:<port>/<repository>@sha256:<digest>
```

Registry mode accepts only repository-owned rendering profiles:

- `full`: every application image represented by the local overlay must be present;
- `matching-only`: exactly the Matching image for the Matching fleet-only certification path.

Arbitrary partial locks are rejected. After Kustomize runs, the renderer verifies that every selected image exactly matches the expected digest reference. A full registry render also rejects any remaining mutable `:local` application image. Output replacement is atomic: a failed render leaves the previous valid output intact.

`kind-load` rendering intentionally performs no registry image substitution and preserves the tracked local image references. This compatibility mode does not change the registry rule that mutable `:local` references are not valid registry runtime identity.

For Matching, registry mode uses the registry OCI manifest digest as runtime/session/Open Barrier identity. The legacy direct-import path uses the exact local Docker image identity because it imports that image into kind.

## Certification runner boundary

`run-local-production-like-certification.sh` owns shared configuration and phase composition. Domain behavior is separated into:

```text
local-certification-framework.sh
local-certification-kafka.sh
local-certification-kubernetes.sh
local-certification-connect.sh
local-certification-workloads.sh
local-certification-bootstrap.sh
local-certification-run.sh
```

These files are sourced modules, not public entry points. Resume state includes the selected image transport so evidence from a registry run cannot be silently reused as kind-load evidence, or vice versa. Image preparation is refreshable because a phase marker cannot prove that mutable external registry/node-cache state still exists.

## 6. Kubelet image-cache policy

After the registry/digest path is established, the canonical local kind cluster configures local-only kubelet image GC:

```text
imageMinimumGCAge: 10m0s
imageMaximumGCAge: 24h0m0s
imageGCHighThresholdPercent: 80
imageGCLowThresholdPercent: 70
```

`manage-simplematch-live.sh verify` reads the effective kubelet config from all four canonical nodes and fails on drift. This policy complements explicit namespace/PV teardown and CRI image pruning; it does not replace lifecycle correctness.

The live registry smoke is the reliability gate for this ordering. It creates the real 1-control-plane/3-worker cluster, establishes the clean baseline, publishes a canonical Matching image through the real publisher, renders the real digest lock, schedules a digest-backed Pod to one worker, and verifies that only the scheduled worker acquires the application repository.

## 7. Hard-reset ownership

Hard reset centralizes destructive ownership rather than reimplementing resource managers:

```text
canonical cluster deletion
-> scripts/manage-simplematch-live.sh delete

registry deletion / data purge
-> scripts/manage-local-registry.sh delete [--purge-data]
```

The hard-reset script may read shared identity constants for planning and postcondition verification, but it does not call the registry deletion primitive directly.

Project-scoped cleanup can remove selected SimpleMatch Compose state, unreferenced SimpleMatch-tagged host images, and repository-generated build/evidence state. Daemon-global operations remain behind explicit aggressive opt-in:

```text
docker container prune
docker image prune --all
docker volume prune --all
docker network prune
docker builder prune --all
docker buildx prune --all
```

The default hard reset does not remove unrelated project resources, `kindest/node` images, upstream Compose images, or daemon-wide builder/buildx cache merely because they are unused.

## Validation contract

Deterministic validation covers:

- lifecycle label ownership and fail-closed namespace/PV observation;
- resource snapshot invariants and baseline-relative growth classification;
- local-only registry configuration;
- registry default plus explicit kind-load fallback;
- legacy normalizer isolation from the registry path;
- semantic digest locks and atomic publication;
- registry digest rendering and kind-load rendering;
- certification transport propagation and resume identity;
- kubelet image-GC configuration;
- hard-reset manager delegation and aggressive-mode boundaries.

The live kind smoke then proves real registry publication, digest rendering, on-demand node pull, baseline behavior, and cleanup. CDC CI remains a regression boundary for the certification runner and Kafka Connect behavior.

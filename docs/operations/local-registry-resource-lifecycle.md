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

```bash
bash scripts/run-local-production-like-certification.sh \
  --image-transport kind-load
```

or:

```bash
SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT=kind-load \
  bash scripts/run-local-production-like-certification.sh
```

The phase declarations select the required transport operations and their dependency order.
Every selected operation executes in the current run.
Build tools retain their ordinary caches, and registry publication still supplies immutable runtime image references.
The existing image adapters own Docker, registry, and kind behavior.
The public `prepare-local-kubernetes-images.sh` command remains available for explicit image preparation.

## 4. Publication and digest lock

Image responsibility is split by concern:

```text
scripts/lib/local-image-inventory.sh
    canonical service/build/repository identity

scripts/lib/local-image-transport.sh
    transport policy, digest-lock semantics, rendering profiles

scripts/prepare-local-kubernetes-images.sh
    explicit registry or legacy kind-load preparation

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

During certification, per-image publication results are combined into one complete immutable `local-images.lock`. Deployment consumes the complete lock with immutable runtime references.

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

`run-local-production-like-certification.sh` remains the normal full-certification entry point.
The [execution contract](../verification/local-certification-phase-dag.md) defines the current profile, result, and ownership rules.

The phase list selects and orders work.
Existing command adapters execute the selected phases.
Current-run results and the completion inventory replace custom historical PASS lookup and output materialization.
Registry image locks retain their recorded byte identity.

Each run requires a new evidence directory.
The default Compose project includes the run ID. An explicitly selected project must contain no existing containers.
`--resume`, the content-addressed PASS cache, and reuse measurement retired on 2026-10-28.
Interrupted certification starts a new run.
Focused diagnostics retain their separate current-state checks and limited claims.

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

Canonical kind deletion also checks Docker's cluster-label inventory independently
of `kind get clusters`. If the kind CLI is missing or discovery fails while node
containers remain, the manager must still verify identity before any generic
container cleanup. A failed Docker inventory query is not evidence that the
cluster is absent: hard reset stops. A manager refusal likewise stops subsequent
cleanup. This is not an atomic reset; selected Compose cleanup may already have
completed before the kind gate is reached.

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

## 8. Build contexts and host cache budget

The root `.dockerignore` excludes host-generated `vcpkg_installed/`: the native
Dockerfile resolves its own dependencies under its container build directory.
The Flyway-specific `deploy/docker/Dockerfile.flyway-runner.dockerignore` takes
precedence over that root file. It retains the Gradle projects, wrapper,
configuration, contracts and runner entry point, excluding generated Gradle
outputs and unrelated documentation/native sources. Keep complete Gradle project
inputs together because migration configures the shared build; do not remove
classpath dependencies from the packaged offline seed merely to reduce size.

Validate context changes through a real image build and the existing Flyway
entry point: all six owners must migrate offline with a read-only root, repeated
migration must leave schema history unchanged, and packaged SQL must match the
source. Use a separate verification tag; replacing a context or building a smaller
image does not retire the previous image or establish aggregate certification.

Use the existing BuildKit GC mechanism with `builder.gc.enabled=true` and an
initial `defaultKeepStorage` budget of `20GB` per daemon. Check the effective
builder policy before changing it; this is a GC policy budget, not a hard limit on
all images, volumes or shared layers. Record build time and cache usage before
lowering the budget, distinguishing a fully cold build from one with retained
dependency caches and from an unchanged warm build. Keep high-cost native/Gradle
caches while they are required by an active build. Paketo `pack-cache-*.build`
and `.launch` volumes have a separate lifecycle: remove only individually
inventoried, run-owned disposable volumes from completed tests or explicitly
stale run-owned cache entries, after confirming no active build/container
reference. Preserve unrelated caches unless their removal has separate explicit
authorization.

For Docker Desktop, compare host free space and the disk image's allocated blocks
with VM filesystem usage. A successful reclaim/TRIM command is not proof that
host space was recovered. Use the [supported reclaim procedure](https://docs.docker.com/desktop/troubleshoot-and-support/faqs/linuxfaqs/)
and measure again; reducing the virtual disk maximum discards its existing data.
BuildKit GC and node-local kubelet GC are independent policies; see the
[BuildKit GC configuration](https://docs.docker.com/build/cache/garbage-collection/).

## 9. Registry digest retention and dry-run

Before retiring a digest, inventory the registry, Docker images, node-local
references, live workloads/Jobs, image locks and retained evidence. Preserve
current artifacts, explicitly required rollback versions and original artifacts
needed to repeat retained verification, including Boot exports. A tag count or an
absent container reference is not sufficient evidence of retirement. Distinguish
OCI index/manifest digests from image config IDs and keep unknown references
until their ownership is resolved.

Write an exact preserve/retire/unknown inventory before any manifest deletion.
For the repository's `registry:3` container, the non-destructive GC preview is:

```bash
docker --context desktop-linux exec simplematch-local-registry \
  registry garbage-collect --dry-run /etc/distribution/config.yml
```

Verify the daemon, registry identity/config path and unchanged manifest inventory
around the preview. GC finds blobs without manifest references; old untagged
manifests can still retain layers, so a dry-run is not a retirement decision or a
promise of recoverable bytes. Actual GC requires a reviewed retirement list,
manifest deletion where appropriate, and a read-only registry or stopped writes
throughout the mark/sweep operation. Follow the [Distribution GC contract](https://distribution.github.io/distribution/about/garbage-collection/).
Registry GC does not remove host Docker images or worker containerd copies.

## Validation contract

Deterministic validation covers:

- lifecycle label ownership and fail-closed namespace/PV observation;
- resource snapshot invariants and baseline-relative growth classification;
- local-only registry configuration;
- registry default plus explicit kind-load fallback;
- legacy normalizer isolation from the registry path;
- semantic digest locks and atomic publication;
- registry digest rendering and kind-load rendering;
- phase selection, fresh transport execution, current-run results, and partial-profile reporting;
- kubelet image-GC configuration;
- hard-reset manager delegation and aggressive-mode boundaries.

Run `ruby scripts/test-hard-reset-safety.rb` for the behavioral deletion gate
regression. It executes the real hard-reset and manager entry points in a temporary
repository, with only fake Docker/kind tools on the child PATH. The checked-in
`scripts/testdata/hard-reset/baseline.json` defines expected exit status, diagnostic,
and deletion effects; assertion failures show the expected/actual difference.
It covers missing kind, failed discovery, manager identity refusal, failed Docker
observation, healthy deletion, an already absent cluster, and dry-run. This test
does not modify or certify a live Docker/kind deployment.

The live kind smoke then proves real registry publication, digest rendering, on-demand node pull, baseline behavior, and cleanup. Local Resource Lifecycle CI protects the local certification contracts; CDC CI remains a regression boundary for the certification runner and Kafka Connect behavior.

# S0 resource inputs and task interface

Source baseline: `7b9d2fd035632aa6797ad1d771a90c96b5062a87`.
Research date: 2026-10-29. Scope: S0 analysis for Issue #197.
The numbers below describe declared resources and planning estimates. They do not establish current runtime capacity or certification PASS.

## Reproduced resource calculation

The offline check rendered the current local Kustomize overlay, then executed the existing resource calculator for both profiles.
It did not contact Docker or Kubernetes, create resources, or run an application.
The calculator uses container requests, falling back to limits when requests are absent.
It accounts for replicas, the largest ordinary init-container request, and Pod overhead.
It rejects unsupported workload shapes instead of silently omitting them.
See [calculator lines 85–126](../../../scripts/local-resource-budget.rb) and [profile selection](../../../deploy/k8s/overlays/local/resource-budget.json).

| Declared quantity | Full profile | Matching-fleet-only profile |
| --- | ---: | ---: |
| Steady memory requests | 41.7421875 GiB | 34.2421875 GiB |
| Bootstrap Job requests | 6.125 GiB | 0.125 GiB |
| Platform plus bootstrap requests | 10.25 GiB | 4.25 GiB |
| Largest declared memory stage | 41.7421875 GiB | 34.2421875 GiB |
| Steady CPU requests | 6,875 millicores | 3,675 millicores |
| Platform plus bootstrap CPU requests | 5,200 millicores | 2,200 millicores |
| Largest declared CPU stage | 6,875 millicores | 3,675 millicores |
| Conservative steady-plus-all-Jobs memory | 47.8671875 GiB | 34.3671875 GiB |

The platform consists of three Kafka brokers, PostgreSQL, and Redis.
The full bootstrap calculation includes the six service Flyway Jobs and topic provisioning.
The staged peak is the larger of steady services and platform-plus-Jobs, not their sum.
The current runner removes its Compose environment before Kubernetes deployment.
Therefore, adding Compose and Kubernetes requests into one alleged concurrent peak would be incorrect.
See [phase execution lines 200–239](../../../scripts/lib/local-certification-run.sh) and [resource report lines 32–59](../../../scripts/local-resource-budget.rb).

Fifteen Matching main containers request 30 GiB. Their status sidecars add 120 MiB.
The Matching local patch also limits each main container to 2 GiB.
Full steady regular-container memory limits sum to 54.71875 GiB, before host overhead.
That limit sum is a conservative envelope, not an observed simultaneous allocation or an admission requirement.
Requests, limits, actual RSS, node allocatable memory, and host physical memory are distinct quantities.
See [Matching local patch lines 21–36](../../../deploy/k8s/overlays/local/matching-scheduling-patch.yaml) and [existing calculation notes](../../../deploy/k8s/README.md#local-host-memory-budget).

## Local planning envelope

The repository reference is 38 GiB of daemon memory. It is not a freshly measured capacity for this machine.
The full declared peak exceeds that reference by 3.7421875 GiB, before control-plane and host processes.
The reduced profile fits that reference by requests but remains PARTIAL.
The current calculator warns without blocking unless its caller selects `--check`.
Its result does not certify per-worker scheduling, swap behavior, available memory, or rollover headroom.
See [runbook lines 62–71](../../verification/local-production-like-certification.md) and [live capacity lookup lines 32–46](../../../scripts/lib/local-certification-run.sh).

For planning, reserve a local environment with approximately 64 GiB available to the daemon and at least eight CPU cores.
This is an engineering estimate for investigation, not a verified minimum or a guarantee.
The estimate provides space above the 41.7421875 GiB request floor for four kind nodes, test JVMs, and the container runtime.
The 54.71875 GiB regular-container limit envelope can still consume most of that memory.
Builds and scenario execution should initially run in controlled stages with limited parallelism.
Measure actual peak RSS, daemon usage, node allocatable resources, and build overlap before setting an acceptance budget.
Do not lower Matching resources solely to fit the estimate.
The existing native direct-core benchmark does not establish full Kafka-connected Pod memory requirements.

The canonical kind input has one control plane and three labeled workers using the pinned node image.
Each worker slot has meaning in placement and fault selection.
Four node containers share one host. They are not four independent capacity allocations.
See [kind input lines 13–49](../../../deploy/kind/simplematch-live.yaml) and [topology validator lines 32–61](../../../scripts/lib/local-kind.sh).

Current disk protection for Docker Desktop requires at least 40 GiB available on its host filesystem.
It also restricts the virtual disk limit to 75% of that filesystem.
That guard explicitly skips non-Desktop contexts, so it does not establish system-Docker or hosted-runner disk sufficiency.
See [storage preflight lines 7–43](../../../scripts/lib/local-docker-storage.sh).
The application image inventory contains seven Spring images, one Flyway image, one verifier image, and one native image.
This excludes platform images, kind node copies, build cache, unpacked layers, PVC data, logs, and evidence.
See [image inventory lines 13–24](../../../scripts/lib/local-image-inventory.sh).
No source file provides reliable byte sizes for that complete set.
Disk and transfer budgets remain unmeasured. Inventory real identities and sizes during S3/S6 before selecting finite budgets.
Do not add overlapping registry, Docker, BuildKit, and node-containerd accounting as independent consumed or reclaimed space.

The production-like Compose profile contains PostgreSQL, Redis, three Kafka brokers, and Connect.
It has no explicit container memory or CPU limits, so its resource peak cannot be derived from a request sum.
See [Compose services](../../../deploy/compose/kafka-connect.production-like.yml).
Testcontainers migration can stage smaller dependency fixtures when requirement equivalence permits it.
A smaller fixture cannot replace requirements that depend on three brokers or fifteen Matching owners.

## Hosted-runner capacity finding

Current official GitHub documentation specifies standard x64 Linux runners as follows:

| Repository context | CPU | RAM | SSD |
| --- | ---: | ---: | ---: |
| Public repository | 4 | 16 GB | 14 GB |
| Private repository | 2 | 8 GB | 14 GB |

GitHub labels these values in GB. Do not silently equate GB and GiB.
GitHub-hosted jobs have a six-hour execution limit.
Sources: [standard runner specifications](https://docs.github.com/en/actions/reference/runners/github-hosted-runners) and [Actions limits](https://docs.github.com/en/actions/reference/limits).

Both standard cases fall below the full profile's declared 41.7421875 GiB memory and 6.875 CPU request envelope.
They also fall below the reduced profile's memory requests.
This is a concrete capacity mismatch for deploying the current complete profile unchanged.
It is not proof of the exact point where a new runtime attempt would fail.
A small kind registry smoke in existing CI does not establish full-profile capacity.
See [current smoke workflow lines 98–121](../../../.github/workflows/ci-local-resource-lifecycle.yml).

S6 must retain complete CI acceptance as blocked until one of these paths has evidence:

1. A measured hosted runner provides sufficient capacity for the complete retained requirement set.
2. Reviewed staging reduces concurrent resource demand while preserving every retained requirement and its required topology.

GitHub lists a 16-CPU, 64-GB, 600-GB Ubuntu larger runner.
That is a concrete candidate to evaluate, not an available or purchased resource for this repository.
Access, account eligibility, billing, actual memory units, image space, and measured duration still require verification.
No S0 action authorizes purchasing capacity or changing to a self-hosted runner.
Source: [larger-runner specifications](https://docs.github.com/en/actions/reference/runners/larger-runners).

## Task names and argument meanings

A static search of tracked Gradle Kotlin build files and convention sources found no declaration of these five proposed task names:
`manifestCheck`, `integrationTest`, `kubernetesTest`, `verifyAll`, and `cleanupVerification`.
This is a source-level collision check. It does not enumerate tasks injected dynamically by every applied plugin.
S1 must confirm the runtime task registry before adding root tasks.
Existing focused Query tasks and QuickFIX certification tasks keep their narrower meanings.
See [Query task declarations](../../../services/query-service/build.gradle.kts) and [QuickFIX task declarations](../../../services/quickfix-gateway/build.gradle.kts).

Use the following proposed properties consistently across local and CI entry points:

| Property | Single meaning |
| --- | --- |
| `verificationClusterMode=dedicated\|disposable` | Dedicated validates or creates the named local cluster. Disposable owns a unique cluster and deletes it after diagnostics. CI requires disposable. |
| `verificationScenarios=<ids>` | Select exact registered scenario IDs for focused tasks. Unknown or empty selections fail. `verifyAll` rejects this property so selection cannot shrink complete scope. |
| `verificationRunId=<id>` | Identify one new run and its resources. Generate a safe unique value when omitted. Execution rejects an existing evidence destination. Cleanup requires an explicit ID. |
| `verificationKeepResources=true\|false` | Local opt-in to retaining run resources after evidence collection. Default false. CI rejects true. It never waives shared-state restoration. |

S0 proposes these spellings for later implementation. They are not existing executable options.
Use a separately validated cluster name only with dedicated mode.
Disposable cluster names derive from the run ID and cannot alias the canonical local cluster.
Reject conflicting old environment variables and new properties instead of silently choosing one.
Keep explicitly selected Docker context and kubeconfig in the run identity.
Do not change the user's global Docker or Kubernetes context to make a test pass.
The present bootstrap requires the cluster to exist and the global context to match.
Automatic cluster creation and explicit per-command context selection are future S3 behavior.
See [current bootstrap lines 141–153](../../../scripts/lib/local-certification-bootstrap.sh).

## Required measurements and preconditions

The following unresolved inputs gate S3/S6 execution and complete acceptance:

- Current selected daemon memory, disk headroom, host/worker architectures, node allocatable resources, and unrelated workload occupancy.
- Exact retained requirement topology, per-scenario duration, peak RSS, build overlap, image bytes, and node-import overhead.
- Approved trading-day artifact and required security fixture inputs, without clock changes or relabeling historical artifacts.
- Pinned image identity and successful execution on every eligible worker, including helpers used by local-path provisioning.
- Image-appropriate probes: shell probes only for images that contain a shell, observed Java entry points for shell-less Spring images.
- Measured failure diagnostics and cleanup durations, including partial setup, timeout, cancellation, and abrupt-runner termination recovery.

The deployment lesson preflight requires worker execution probes in addition to image metadata checks.
A missing-layer or snapshot failure can exist even when image inspection succeeds.
See [deployment preflight lines 17–38](../../agents/deployment-test-lessons.md) and [shell-less image lessons lines 49–61](../../agents/deployment-test-lessons.md).
S0 records these as unmeasured or required inputs. It does not classify them as current runtime failures.

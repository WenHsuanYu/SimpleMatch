# RM-1 Risk-to-Matching deployed E2E verification

`./gradlew verifyKubernetes` builds current images and uses Jupiter to prove one fresh BUY command
crosses the durable Risk admission/outbox boundary, Debezium, and `matching.commands`. The host
adapter creates real kind nodes in the selected Docker engine and deploys real PostgreSQL, Kafka,
Account, Risk, Connect and verifier workloads. This is local deployed evidence; its scope ends at
the command topic. Matching execution, restart/replay, HA and aggregate `full-local` certification
have separate gates.

## Ownership and design

`tools/kubernetes-tests` owns preflight, image-build receipts, cluster and namespace lifecycle,
fixture provisioning, bounded diagnostics, evidence collection and cross-layer Jupiter assertions.
The existing `tools/risk-matching-e2e-verifier` owns typed Market Reference validation, scenario
selection, gRPC submission/reconciliation, Kafka observation, protobuf decoding and semantic checks.
The host reuses that verifier rather than duplicating its protocol assertions.

`deploy/k8s/verification/risk-matching-e2e-verifier-job.yaml` owns the verifier's execution policy,
security, resource limits, mounts and CLI arguments. `matching-open-barrier-job.yaml` invokes the
native fixture publisher before Risk starts. Run ConfigMaps are immutable and contain only test
inputs. Versioned Account/Risk Flyway migrations, canonical RF3 Kafka topics with 15 command
partitions, and the Risk outbox connector remain required.

The selected boundary needs no Redis, Account outbox connector or running Matching fleet. The
native Open Barrier still carries the actual built Matching image identity. Removing these unused
workloads narrows dependencies without changing the selected business assertion. Other scenarios
retain their own required deployments and interfaces.

Each run receives a fresh UUID, namespace, account and evidence directory. A cross-process lease
serializes runs using the same Docker daemon. The adapter rejects existing evidence destinations,
foreign or abandoned resources, invalid topology, changed source/image identities and unsupported
capacity. It never adopts an older namespace or resumes an interrupted verdict.

## Preconditions and local verification

Run from the repository root with the project's Java/Gradle toolchain, Docker, kind, kubectl, Bash,
Ruby, jq, GNU tar and timeout available. The command does not install software or acquire credentials.
Image builds and initial platform-image pulls need their normal dependency network access.

Select the Docker engine explicitly. On this workstation use `desktop-linux`; its kind node
containers are real Docker Desktop containers. The managed Desktop Kubernetes system containers
belong to a separate cluster. The harness uses its own private kubeconfig, so the current kubectl
context does not choose its target.

Docker Desktop must share `/tmp/simplematch-kubernetes-coordination`. The adapter stores private
per-run ownership markers and kubeconfigs below that directory. The latest creation receipt is atomically mirrored there
before updating evidence, so interrupted dedicated creation cannot be adopted as prepared and an
old partial receipt cannot later delete a completed dedicated cluster. Each new node receives its run's
UUID marker as a read-only bind mount. Keep this directory and the original ownership receipt until
retained resources have been cleaned. Configure the exact share in Desktop's Resources > File
sharing before running; the adapter does not change Desktop settings or restart it.

The current selected workload requests total 8 GiB and 3.5 CPU. Preflight adds the verifier's
512 MiB request and a 4 GiB kind/control-plane reserve, and requires at least eight Docker CPUs.
Native Docker requires that complete budget in current host available memory. Desktop requires it
in current guest available memory and retains a separate 1 GiB of host-tool headroom. A new
read-only, 32 MiB observer uses the selected PostgreSQL image to read the VM's memory; it removes
its own container and anonymous volumes. This avoids reserving the VM's existing memory twice,
without changing Desktop settings. The shared storage guard checks Desktop's actual backing filesystem. Worker image filesystems must remain below 75% use before and
after import, before a new build against existing workers, and after bootstrap. The control plane
must pass its stability observation after bootstrap and before namespace deletion. Requests describe declared
scheduling requirements; they are not measurements of peak RSS.

Run the offline suite and static analysis before a deployment:

```bash
./gradlew :tools:kubernetes-tests:check staticAnalysis --no-daemon
bash scripts/test-risk-matching-e2e-manifest.sh
bash scripts/test-matching-topic-cutover.sh
```

The ordinary `test` task excludes the `kubernetes-live` tag. Its process, filesystem and real
kubectl/API-fixture tests validate adapter behavior; only `verifyKubernetes` executes the deployed
business scenario.

## Run with a dedicated or disposable cluster

The default `dedicated` mode validates and reuses `simplematch-live`, or creates it when missing.
It requires one Ready control-plane node and three Ready workers with the canonical pool and unique
slots `0`, `1`, `2`, bound to the original kind Docker containers. Each invocation still creates a
fresh namespace and data. Successful preparation preserves the dedicated cluster after namespace
and PVC/PV cleanup. A partial cluster created by this run can be removed only with its exact owner
proof; an existing dedicated cluster is never deleted by this harness.

Use a new evidence path on every invocation:

```bash
JAVA_TOOL_OPTIONS='-Xms32m -Xmx512m -XX:+UseSerialGC' \
./gradlew verifyKubernetes --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx512m -Xms32m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC -Dfile.encoding=UTF-8' \
  -PkubernetesDockerContext=desktop-linux \
  -PkubernetesMode=dedicated \
  -PkubernetesTradingDay=2026-08-17 \
  -PkubernetesEvidence=out/verification/rm1-dedicated-01
```

The bounded Gradle heap and host-side child JVM options avoid reserving the workstation's larger
default heaps during capacity-sensitive work. These options do not enter the deployed application
Pods. It does not lower any workload request. The approved historical delivery
keeps its original trading day. `kubernetesDeliveryManifest` may select another readable approved
manifest for the explicitly matching `kubernetesTradingDay`.

Set `-PkubernetesMode=disposable` to create a unique four-node `simplematch-s3-<run-prefix>` cluster.
Normal teardown removes its namespace/PVs and its exact owned node containers and volumes.
Disposable mode is the isolation boundary for node-level faults; a namespace in the dedicated
cluster does not isolate shared nodes.

The normal entry acquires platform images, builds the selected application/helper images using
ordinary deterministic caches, and writes a new build-input receipt. Every invocation executes the
selected live checks anew. Receipt checks bind source bytes, executed Gradle metadata, daemon and
immutable image identities. Each selected image is exported as a separate `linux/amd64` archive. Its config digest is checked
against the actual bytes while the host reference remains unchanged. Worker CRI identities must
match those exported config digests; the filtered index can differ from the host's multi-platform
index. Spring probes use the launcher's `--` direct execution strategy, as defined by the
[CNB platform specification](https://github.com/buildpacks/spec/blob/main/platform.md#launcher),
so the tiny run image needs no Bash. Worker execution probes establish usability, and actual deployed Pod image IDs are checked
before and after the command assertion. A tag or image listing alone is
insufficient proof.

## Retention and exact cleanup

Add `-PkubernetesRetain=true` for explicit local inspection. `summary.json` records the business
assertion result and `cleanup: RETAINED`; retention never counts as successful cleanup. Do not
manually start another conflicting run against retained resources.

To clean that run, pass its original `ownership.json` and a new cleanup-report destination:

```bash
./gradlew cleanupKubernetesRun --no-daemon \
  -Dorg.gradle.jvmargs='-Xmx1g -Xms128m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' \
  -PkubernetesOwnership="$PWD/out/verification/rm1-dedicated-01/ownership.json" \
  -PkubernetesCleanupEvidence="$PWD/out/verification/rm1-cleanup-01"
```

Cleanup rechecks the recorded Docker engine, exact node IDs, cluster/namespace identities, current
creation state and read-only owner mounts. Disposable teardown addresses each validated full
container ID directly; it does not reselect targets through a cluster-name deletion command. Namespace and disposable-node cleanup have independent attempts, so an
unavailable API does not block deletion when independent Docker ownership is proven. An already
removed disposable target succeeds without querying its old API. A prepared dedicated cluster
remains intact. Unrelated images, named volumes and Desktop system containers are preserved.

The cleanup command writes a separate cleanup-only result and never changes the original run's
business verdict. If an ownership check fails, preserve the evidence and investigate that exact
resource; do not use a blanket prune or delete another cluster to obtain a green report.

Connect registration uses REST inside an owned Ready Connect Pod. Its ingress policy permits
Connect peers on port 8083; the API Service Proxy originates on the control-plane node and is
not an authorized peer. The harness preserves that policy, checks any existing connector's
configuration, does not blindly repeat an uncertain POST, and requires the connector and its
tasks to reach `RUNNING` before admission. A namespace or service rollout is not sufficient
proof that the harness's chosen management path is reachable.

## Business assertion

After migrations, topic provisioning, native Open Barriers and service readiness, the adapter
registers the Risk outbox connector and requires its connector/tasks to be `RUNNING`. It provisions
a fresh Account daily cash limit for the explicit trading day, then starts the verifier Job.

The verifier validates the mounted artifact and checksum, selects an eligible instrument using
`RiskMatchingScenario`, and snapshots all 15 command-partition end offsets before submission. It
submits one real BUY/LIMIT/ROD board-lot order through
`simplematch.risk.v2.OrderAdmissionService/SubmitNewOrder`. One timeout budget covers submission,
reconciliation and Kafka observation.

`SYNCHRONOUS_ACCEPTED` means the initial response contains validated accepted identities and route.
`RECOVERED_ACCEPTED` permits an ambiguous gRPC `UNAVAILABLE` result only when the existing public
`GetAdmissionOutcome(command_id)` reaches durable `ACCEPTED`. The verifier never resubmits the order
or drives Risk recovery. `REJECTED`, `NOT_FOUND`, identity mismatch or deadline expiry fails.

Only records after the pre-submit offsets count. At-least-once duplicates are accepted only on the
same partition with byte-identical payloads; conflicting same-key records fail. Host Jupiter then
compares the durable admission journal, Risk outbox and delivered Kafka record: account/order/
command IDs, trading day, artifact hash, routing version and partition must agree, and outbox payload
bytes must exactly equal the Kafka bytes.

The local overlay explicitly disables Risk's CDC backpressure controller. This scenario directly
proves durable outbox delivery and does not insert synthetic CDC health. Production defaults and
the separately verified CDC lag safety controller retain their own scope; this acceptance does not
certify that controller.

## Evidence and failure handling

The evidence root contains `preflight.json`, `ownership.json`, `summary.json`, observed worker image
and filesystem identities, control-plane stability observations, bounded diagnostics, and the durable
`risk-admission.json` / `risk-outbox.json` cross-check inputs. `summary.json` separates `assertions`,
`diagnostics` and `cleanup`, and retains redacted primary, diagnostic and cleanup failure causes.
A required failure prevents overall PASS.

The `verifier/` subdirectory contains `selected-instrument.json`, `request.json`,
`admission-submit.json`, `admission-outcome.json`, `matching-offsets-before.json`,
`matching-command-record.json`, `matching-command-decoded.json` and `verifier-verdict.json`.
The typed verdict must pass, the normalized outcome must be terminal `ACCEPTED`, and the outer
Jupiter assertions and lifecycle must succeed. A helper's marker file is not a business verdict.

The image wrapper writes `.exit-code` and `.ready`, waits while the host copies `/tmp/evidence`,
and exits with the verifier status after `.collected`. This bounded hand-off exists because
`kubectl cp` requires a running container. The Job deadline remains a separate upper bound.

Before deletion, diagnostics preserve bounded inventory, events, owned Job/Pod termination state
and available logs, including failed attempts of retryable migration Jobs. A successful retry
satisfies the canonical Job policy; an earlier failed attempt remains visible in the evidence.
The verifier itself retains `backoffLimit: 0`. Partial kind creation retains owned nodes long enough
for diagnostics before exact teardown. Diagnostics and cleanup failures remain separate from the
original business/setup failure.

## Certification boundary

The [restart/equivalent-replay gate](risk-matching-restart-replay.md) has its own retained-environment
workflow. This Jupiter entry does not adopt that workflow or grant its coverage to a command-topic
PASS. The [capability inventory](../status/phase-1-capability-inventory.md#rm-1-load-the-artifact-in-risk-and-publish-matching-commands)
records source-specific RM-1 status. An executable entry or a passing adapter fixture does not
establish deployed, aggregate or production acceptance for a new source revision.

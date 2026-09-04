# Local production-like Kubernetes workload startup investigation

> Archived on 2026-09-04. This document records historical deployment investigation and is not a
> current executable runbook. See [`docs/archive/README.md`](README.md) for the active replacements.

## Scope and current status

This note records the investigation of the local production-like certification's Kubernetes
workload startup phase. It covers the repository-owned local gate only. It is not external
production certification and does not require pushing images to GHCR or Docker Hub.

The static deployment checks and the Compose/Kafka/Flyway dependency checks pass after the fixes
described here. A fresh 2 GiB-per-Matching-pod fleet-only run has also verified all 15 logical
owners on the restarted local kind node. It is deliberately a `PARTIAL` local result: it proves the
Matching fleet wiring, but it does not replace the full cross-service certification or an external
production certification.

The historical in-cluster cross-service run `kubernetes-resume-20260815-9` also completed the
Kubernetes runtime phases that were in scope at that time: all seven Flyway Jobs, workload apply,
Risk and Account Kafka Connect connector registration, Open Barriers, seven Java rollouts,
QuickFIX, and the 15-pod Matching fleet. Its report remains
`PARTIAL` because image build and Compose phases were intentionally skipped and the namespace was
kept for evidence collection. This is deployment/runtime evidence, not completion of the native
Matching ring/replay/performance work in #127, #128, and #136.

## What happened

The first report failed while loading a Spring Boot image into kind with `ctr: wrong diff id`.
After that transfer issue was isolated, the certification reached Kubernetes but the workload
startup gate still failed. The failures appeared in dependency order rather than as one defect:

1. Spring services tried to read profile-specific ConfigMaps such as
   `simplematch-platform-config-kubernetes`; their service accounts were allowed to read only the
   base ConfigMap, so startup received HTTP 403.
2. The Matching StatefulSet referenced `matching-daily-artifact`, but the approved artifact had
   not yet been applied to the certification namespace.
3. The default current-day artifact path had no approved artifact for the current UTC day. Using
   the previous day's artifact silently would have made the evidence dishonest, so the gate was
   changed to require an explicit trading day and to verify the generated ConfigMap name.
4. Flyway Jobs ran with a read-only root filesystem. Gradle first tried `/workspace/.gradle`, then,
   after the user cache was redirected, still tried the project cache and problem-report paths
   under `/workspace`.
5. Query and Risk were missing the local session trading-day/image-digest inputs, and QuickFIX
   inherited the production profile. They therefore failed their own startup validators even
   though Kubernetes had scheduled the Pods.
6. The historical `marketdata-publisher` runtime exited normally. Kubernetes treated that clean
   exit as a Deployment restart loop; keeping it alive with a fake process would have hidden the
   intended architecture. #119 subsequently removed the runtime rather than retaining a disabled
   Deployment.
7. A full local fleet with a temporary 4 GiB request per Matching pod could not fit alongside the
   Java workloads on the previous 32 GiB kind node. The scheduler correctly reported
   `Insufficient memory`; this was capacity pressure, not an engine out-of-memory diagnosis.
8. Applying Flyway Jobs and application Deployments/StatefulSets in one operation allowed Java
   Pods to consume memory while Gradle migration processes were starting. On the constrained node,
   the Flyway containers were subsequently `OOMKilled`. Waiting for Jobs after one combined apply
   did not prevent this race; the resources had already been created.
9. Matching stayed unready when the required Open Barrier messages were absent. The kubelet later
   terminated the process after the startup probe failed; that termination must not be interpreted
   as the original cause.
10. After a Docker Desktop restart, an old Kubernetes Endpoints object still held the former Docker
    bridge address for Kafka. Pods then received `connection refused`; recreating a fresh namespace
    and Compose project discarded that stale operational state.
11. The original live verifier sorted StatefulSet pod names lexically (`matching-10` before
    `matching-2`) and rejected the repository's exact local image tag. It therefore produced a
    false failure after the fleet had become Ready.
12. The local Matching profile had 129 instruments on its largest partition. With 1,024 resting
    orders per instrument, the runtime needs `129 * 2 * 1,024 + 2 = 264,194` output slots,
    including the end marker. The configured 262,144 slots were therefore correctly rejected at
    startup; the local power-of-two capacity is now 524,288, with the same bound for pending
    publications.
13. The Java NetworkPolicy allowed the Kubernetes Service CIDR but not the kind control-plane
    address used after Service-to-endpoint translation (`:6443`). Spring Cloud Kubernetes then
    timed out while reading ConfigMaps. The local overlay now allows the canonical kind Docker
    network to reach the API port; this is a local-kind rule, not a production network policy.
14. The local QuickFIX PVC patch initially replaced the complete `volumeClaimTemplates` item and
    dropped `accessModes` and `resources.storage`. Kubernetes rejected the StatefulSet before
    startup. The patch now repeats the complete local RWO PVC contract (`2Gi`, `ReadWriteOnce`,
    and `simplematch-rwo-pod`).

The final live run is therefore intentionally gated by both dependency order and adequate Docker
capacity. A 1 GiB Docker limit cannot run this workload set. The successful fresh fleet-only run
used the local 2 GiB Matching overlay on a restarted kind node with about 38 GiB allocatable
memory. A full 15-owner plus Java workload gate needs separately measured capacity; it must not
claim that the 2 GiB fleet-only result is equivalent to that larger scenario.

## Startup dependency model

```mermaid
flowchart TD
    A[Approved immutable artifact for trading day] --> B[Namespace inputs]
    B --> C[Platform resources\nServiceAccounts, RBAC, ConfigMaps, Services, Leases]
    C --> D[Flyway Jobs\nsequential, one service at a time]
    D --> E[Schema-ready gate]
    E --> F[Java Deployments and QuickFIX StatefulSet]
    E --> G[Matching StatefulSet\n15 logical owners]
    F --> H[Readiness and rollout gate]
    G --> H
    H --> I[Live Matching fleet verification]

    J[Read-only container root] --> K[/tmp Gradle user cache]
    J --> L[/tmp Gradle project cache]
    J --> M[/tmp writable project workspace]
    K --> D
    L --> D
    M --> D

    N[Docker memory capacity] --> D
    N --> F
    N --> G
```

The important ordering is `platform resources -> Flyway -> runtime workloads`. The application
Pods are not merely checked later; they are created only after the migration Jobs complete. Within
the Flyway step, the adapter submits all seven independent Jobs first and then supervises each
bounded completion. This preserves the schema barrier while avoiding seven serial Job-startup
delays; a failed or timed-out Job still collects the same diagnostic evidence and fails the phase.

## Root causes and selected repairs

| Root cause | Evidence | Repair selected | Reason for selection |
| --- | --- | --- | --- |
| Paketo/bootBuildImage layer metadata was not accepted by kind's containerd import path | `wrong diff id` during `kind load docker-image` | Retain the `-boot` source tag and build a flattened disposable kind-transfer image | Preserves the Boot image workflow while adapting only the local kind transfer boundary |
| Profile-specific Spring Cloud Kubernetes sources were implicitly enabled | Startup logs showed 403 for `*-kubernetes` ConfigMaps | Disable profile-specific ConfigMap and Secret sources in the base runtime; keep narrow RBAC | Avoids wildcard RBAC and preserves the intended explicit configuration boundary |
| The artifact ConfigMap was not present before Matching was created | Matching Pod had a `FailedMount` for `matching-daily-artifact` | Apply the approved immutable delivery manifest under the stable local name before Kustomize resources | Makes the input explicit and reproducible |
| Current-day artifact was absent | Certification stopped instead of using stale `2026-08-11` data implicitly | Require `SIMPLEMATCH_CERTIFICATION_TRADING_DAY` when using an approved historical fixture and verify the generated name | Prevents stale evidence from being presented as current-day certification |
| Gradle user cache selected a read-only location | `/workspace/.gradle/9.7.0/fileHashes` could not be created | Select `/tmp/gradle` with `GRADLE_USER_HOME` | Gradle user cache is temporary runtime state |
| Every Flyway Pod started with an empty Gradle distribution cache | Seven one-shot Jobs independently downloaded the pinned Gradle distribution | Prewarm the pinned wrapper distribution in the Flyway image and seed each Pod's writable cache; use a BuildKit cache mount while building the verifier image | Avoids repeated network downloads without sharing mutable Gradle state between Pods |
| Gradle project cache remained under the source tree | The same failure persisted after the user cache change | Select `/tmp/gradle-project` with `--project-cache-dir` | Separates project cache from source and keeps the root filesystem read-only |
| Gradle problem reports/build outputs remained under `/workspace/build` | `Could not create problems-report directory '/workspace/build/reports/problems'` | Mount `/workspace/build` as a writable `emptyDir` | Keeps generated build output out of the image layer |
| Gradle validates multi-project directories as writable | `configured projectDirectory ... can't be written to` | Copy the image source into `/tmp/simplematch-workspace` and execute Gradle there | Keeps the image source immutable while satisfying Gradle's project-directory contract |
| Migrations and runtime Pods were created together | Flyway and Java Pods competed for memory; Flyway Pods were `OOMKilled` | Split the rendered manifest into platform, migration, and workload documents; submit the seven Flyway Jobs together, supervise their bounded completion, then apply workloads | Establishes a real dependency boundary without serial migration startup overhead |
| Matching startup waited for Open Barriers | Matching remained Running but did not become Ready, then failed its startup probe | Publish one valid barrier for every partition from the approved artifact, current image identity, and session | Preserves the readiness contract instead of weakening the probe |
| Kafka coordinator was still converging when Matching queried its committed offset | A cold-start `matching-0` attempt returned `NOT_COORDINATOR` before the broker group stabilized | Retry only `NOT_COORDINATOR` with five bounded attempts and 100/200/400/800 ms backoff; preserve fail-closed behavior for every other error | Absorbs a transient broker-coordinator transition without accepting an unknown recovery boundary |
| Full 4 GiB local fleet exceeded prior node capacity | Scheduler emitted `Insufficient memory` | Keep the production-shaped request in templates; use the documented 2 GiB local override for a focused fleet gate | Keeps local scheduling accommodation explicit and avoids mislabelling scheduler pressure as an OOM |
| Docker restart left a stale Kafka endpoint address | Pods connected to the prior bridge IP and got `connection refused` | Recreate the scoped namespace and Compose project for the rerun | Does not reuse state that no longer names the running Docker network |
| Fleet verifier used lexical pod sorting and production-only image acceptance | All 15 Pods were Ready, yet the verifier rejected the result | Sort ordinal/partition values numerically and permit only the exact local tag under an explicit local flag | Retains strict production digest validation while making the local gate evaluate its documented input |
| Local session inputs were absent | Query reported `query market-reference trading day is required`; Risk lacked the matching digest/day | Local overlay reads the values from `matching-session-config` | Keeps local identity explicit and aligns Risk, Query, and Matching |
| QuickFIX inherited `production` | Validator rejected a production profile without the production ConfigMap source | Local overlay sets `SPRING_PROFILES_ACTIVE=local` and constrains local resources | Uses the local contract without weakening staging/production templates |
| Superseded publisher runtime exited cleanly | Deployment restarted an intentionally completed process | #119 removes the obsolete Deployment, image, and connector | Keeps the offline artifact architecture explicit instead of adding a keepalive workaround |
| Diagnostic resources consumed node capacity | Scheduler reported `Insufficient memory` | Delete only certification namespaces created by the investigation; preserve `default` resources; use a sufficiently sized Docker limit | Cleans the scoped test environment without changing production-shaped requests |
| Matching local output bound was smaller than the mathematical burst | `matching output ring must hold one worst-case event burst and its end marker` | Increase the local power-of-two output and pending-publication capacities to 524,288 | Keeps the 1,024-order local functional bound honest instead of weakening the runtime check |
| Java API traffic was blocked after kind Service translation | Spring Cloud Kubernetes ConfigMap reads ended in `SocketTimeoutException` | Add a local-only API egress policy for the kind network and Kubernetes API port | Restores the required local ConfigMap/Lease path without widening staging or production policy |
| QuickFIX local PVC patch omitted inherited fields | Kubernetes rejected missing `accessModes` and `resources.storage` | Preserve the complete RWO PVC spec while adding the explicit local StorageClass | Makes the StorageClass guarantee executable rather than declarative only |
| Kafka Connect had no disruption guardrail | Static review found no PDB for the two-worker deployment | Add `minAvailable: 1` PDB | Matches the accepted planned-disruption contract |

## How the issue was narrowed down

The investigation used a red/green loop: reproduce one failure, capture the exact phase and
container log, add a focused regression assertion, make the smallest source/configuration change,
then rerun the original gate.

Useful observations were:

```bash
kubectl get pods -o wide
kubectl describe pod POD
kubectl logs pod/POD --all-containers
kubectl get events --sort-by=.lastTimestamp
kubectl describe node
docker image inspect simplematch/flyway-runner:local
docker exec KIND_NODE ctr -n k8s.io images ls
```

The certification now records each phase under its evidence directory and writes `failed_phase`
and `note` into `report.md`. This matters because a report marked `FAILED` without identifying
whether image load, input application, or workload startup failed is not actionable evidence.

Successful phases also write a source-bound marker under `evidence-dir/phases/`. A later diagnostic
run can use `--resume` with the same evidence directory and namespace to reuse those phases. The
runner refuses to reuse markers when the relevant source/configuration fingerprint, cluster,
trading day, or namespace differs; this prevents an old phase result from being presented as new
runtime evidence.

The focused regression checks are:

```bash
bash scripts/test-flyway-services.sh
bash scripts/test-kubernetes-overlays.sh
bash scripts/test-local-production-like.sh
```

## Alternatives considered

- Grant service accounts wildcard access to profile-specific ConfigMaps. Rejected because it
  violates least privilege and hides the configuration-source mistake.
- Remove Kubernetes Config Data imports from the applications. Rejected because it would stop
  exercising the production-shaped configuration boundary.
- Use the previous day's Market Reference artifact without an explicit date. Rejected because it
  would make the certification report claim the wrong trading day.
- Lower the base production resource requests to fit a small Docker VM. Rejected because it would
  change the production-shaped contract. The local overlay may make only the documented local
  scheduling adjustment for Matching and QuickFIX.
- Keep the superseded publisher alive with a sleep loop. Rejected because a clean exit is the
  correct state for a removed runtime component.
- Apply everything at once and wait for Jobs afterward. Rejected because the memory race occurs
  during creation; the selected fix creates runtime workloads only after migrations complete.

## Final clean Matching fleet retest

The fresh local run used `--matching-fleet-only --keep-resources` against the explicitly approved
`2026-08-11` artifact. This mode starts clean local Kafka, applies platform inputs and the
Matching StatefulSet, publishes all required barriers, and then verifies the fleet. It intentionally
skips Flyway and the other runtime workloads, so its report remains `PARTIAL` by design.

The runner's first `final17` report remains `FAILED` because it captured the verifier defect before
the numeric-sort and local-tag fixes. It is retained as historical evidence rather than altered.
After those fixes, the same live namespace passed the authoritative verifier:

```bash
KUBECONFIG=/tmp/simplematch-live-kubeconfig-restarted \
  bash scripts/verify-matching-fleet-live.sh \
  --namespace simplematch-local-cert-final17-20260812 \
  --allow-shared-node --allow-local-image
```

It reported 15 Ready Pods, 15 Lease holders, 15 RWOP PVCs, and valid logical ownership on the
single local node. This evidence closes the local fleet-startup uncertainty only; a clean complete
cross-service report still remains the broader local certification gate.

## Selected rerun

After Docker Desktop has enough memory and the `simplematch-live` kind cluster is Ready, use an
approved artifact whose date is explicit. The repository currently has the approved `2026-08-11`
fixture:

```bash
SIMPLEMATCH_KIND_CLUSTER_NAME=simplematch-live \
KUBECONFIG=/tmp/simplematch-live-kubeconfig-new \
SIMPLEMATCH_CERTIFICATION_TRADING_DAY=2026-08-11 \
SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST=tools/market-reference-builder/data/2026-08-11/delivery/manifest.yaml \
bash scripts/run-local-production-like-certification.sh
```

When a diagnostic run uses `--keep-resources`, a later attempt can continue from its successful
phase markers:

```bash
SIMPLEMATCH_CERTIFICATION_NAMESPACE=<same-namespace> \
SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR=<same-evidence-directory> \
KUBECONFIG=<same-kubeconfig> \
bash scripts/run-local-production-like-certification.sh --resume --keep-resources
```

This is a bounded rerun convenience, not a way to skip a changed manifest or claim a new fault
test from an old result.

Do not use `--keep-resources` for a normal run. It is useful only while diagnosing a failure. The
runner removes its own Compose project and generated namespace on exit while retaining the
evidence directory.

## Boundary of the result

A passing local report proves repository-owned local wiring, production-shaped configuration
contracts, migration ordering, and the disposable kind Matching fleet. It does not prove external
registry publication, three physical Kafka brokers, 15 physical Kubernetes nodes, production CSI,
external PostgreSQL/TLS, or external FIX interoperability. Those remain staging/production
templates with placeholders.

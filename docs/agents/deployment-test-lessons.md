# Deployment Test Lessons

This is the living checklist for Compose, Docker, kind, and Kubernetes deployment tests. It retains
only low-frequency environment/platform failures and genuine distributed-system transients that
remain useful after deterministic defects are fixed. Product, verifier, fixture, and hygiene
mistakes are removed once their logic or workflow has been corrected, so this table does not imply
that those failures are expected to recur.

## Run preflight

Before applying deployment resources or injecting a deployment fault:

1. Read the lessons below and run every prevention check that applies to the selected profile.
2. Confirm the Docker daemon, canonical kind cluster, Kubernetes context, namespace, and expected
   worker topology before selecting any Pod, Node, container, image, PVC, or topic.
3. Resolve runtime identities from the deployed artifact and API objects. Do not substitute a unit
   test fixture, expected ordinal, or remembered symbol for an observed assignment.
4. Confirm that the run has an isolated namespace and clean, run-owned evidence directory. Reuse
   prior phase evidence only when the runner's resume contract verifies the same source, cluster,
   trading day, and namespace.
5. Inventory Docker resources and generated caches before and after the run. Cleanup must preserve
   active resources and remove only exact run-owned disposable resources.
6. Select an explicit trading day and readable approved delivery manifest before expensive builds.
   A historical artifact must retain its original day; never relabel it or adjust the host clock.
7. For kind-loaded helpers, compare matching identity types: Docker's immutable `.Id` may identify
   an image config or an OCI index, while CRI `.status.id` identifies the config. When they differ,
   verify the exact reference's containerd target digest instead. Require an execution probe on
   every eligible worker as well; metadata equality alone cannot prove usable content.

If a preflight check fails, stop before fault injection and record the result as an environment or
precondition failure. Do not make the test green by changing Kafka offsets, deleting authoritative
data, weakening fail-closed behavior, or guessing a replacement target.

The #162 preflight found a missing current-day approved manifest, an unset image-reference argument
in the fleet wrapper, and a false verifier identity rejection caused by comparing an OCI index
with an image config. The safe corrections are explicit historical artifact selection, the existing
image-transport resolver, and typed digest comparison plus execution probes. Checked-in command and
identity baselines cover the latter two corrections. Preserve the original failed reports in
`out/certification/issue-162-deployment-20261007-r1`, `issue-162-deployment-20260827-r3`, and
`issue-162-runtime-baseline-20260827-r3`; they remain diagnostic failures, not business-flow evidence.
These corrected deterministic defects are not recurring platform failures in the table below.

The #164 run found two corrected shell-less-image observation mistakes. An initial
node execution probe assumed every image provided `/bin/sh`; the normalized
Paketo runtime did not. The safe probe uses its observed Java binary for Spring
images and retains shell execution for helper images, after immutable identity
comparison. The original failed report remains in
`out/certification/issue-164-deployment-20260827-r2`; corrected probes execute on
every eligible worker before workload application. The first recovery scenario
then failed before restart because Gateway also lacks `cat`. Its retained FAIL
is `out/certification/issue-164-gateway-recovery-20260827-r1/verdict.json`.
The observer now reads only the two named files from the verified, bound
kind-local PV on the actual owner node, with node/container ownership, mount,
storage-root and deadline checks. Redaction tests and a read-only deployed
observation cover that seam; no shell, schema or production endpoint was added.
These are corrected verifier assumptions, not evidence of runtime corruption
and not recurring platform failures in the table below.

The first #193 focused Query lifecycle preflight stopped before namespace creation:
`kind load docker-image` imported a locally incomplete multi-platform OCI index with
`--all-platforms` and reported a missing content digest. This repeats the platform-export
boundary covered by DT-014/DT-017. The focused adapter now exports only `linux/amd64`,
derives the child configuration digest from that export, imports the selected platform,
compares the corresponding CRI configuration identity, and executes the image on each
eligible worker. It also checks node image-filesystem headroom before importing. No
canonical tags, cluster, or runtime data are removed. Preserve
`out/verification/issue-193-lifecycle/runtime-r1/runtime/verdict.json` and its private
import diagnostic as FAIL; successful image metadata or export is not Runtime PASS.

The second #193 attempt completed all nine worker/image execution probes, then failed
while converting the rendered overlay: its original namespace disagreed with the
new run namespace. No platform workload was applied, and the owned namespace was
destroyed and verified. The adapter now performs client-only rendering without an
override namespace, parses kubectl's multiple JSON documents, then assigns the run
namespace only to its explicit resource subset. Source preflight exercises that
conversion before any deployment. Retain `runtime-r2/runtime/verdict.json` under
the same evidence root as FAIL with its teardown proof; this was a corrected
test adapter defect, not a Query or Kubernetes authorization result.

The third #193 attempt found that the existing PostgreSQL StatefulSet uses
`OnDelete`, for which `kubectl rollout status` is unsupported. The adapter now
observes the workload's actual desired/Ready replica counts without changing its
update strategy. This failed before Query startup, and exact-owned teardown
completed. Preserve `runtime-r3/runtime/verdict.json` and its teardown proof as
FAIL rather than Query HTTP evidence.

The fourth #193 attempt reached healthy PostgreSQL and all three brokers without
restarts, but its adapter used a 120s wait for the existing topic-provisioning Job,
whose manifest allows 600s for bounded topic describe/create/configuration work.
The Job was progressing when that adapter wait expired. The focused runner now
uses the existing 600s startup contract; it does not change the later 20s drain,
90s stop or 120s catch-up acceptance limits. Preserve the original timeout and
verified teardown in `runtime-r4/runtime/`; no Query process had started.

The fifth #193 attempt completed topic provisioning and the existing Query Flyway
task, but its verifier counted all successful history rows as versioned migrations.
The corrected check retains the actual history, distinguishes versioned entries
from schema-management entries, and requires successful V1/V2/V3 migrations.
The attempt stopped before Query startup and completed owned teardown. Preserve
`runtime-r5/runtime/verdict.json` as FAIL; an exit-zero Flyway command alone does
not replace history validation.

The sixth #193 attempt validated the actual Flyway history, then its synthetic
publisher failed at Kafka Producer construction: its delivery timeout did not
cover the client's request timeout plus linger. A no-broker regression now
executes the real constructor through the fixture's configuration-check mode;
the original three-second RED reproduces that exact exception, and explicit
request/delivery budgets correct it. Failed Job logs are retained before teardown.
The original publisher log is `31-fixture-kafka-red.log`; retain the attempt's
FAIL/teardown under `runtime-r6/`. The recovery observer also captures source
boundaries before Query restart and includes startup in its 120s catch-up budget.

The seventh #193 attempt delivered its baseline fixture, then Query replicas did
not start: the focused adapter supplied a new artifact ConfigMap name while the
existing local overlay still required `matching-daily-artifact`. The adapter now
supplies that existing name/mount and checks all required volume references before
deploying Query. It retains Pod/event diagnostics privately before teardown, and
the cleanup race regression ensures stale running-container observations cannot
prevent namespace deletion; capture failures remain FAIL even after verified
cleanup. Preserve `runtime-r7/runtime/` as FAIL, not HTTP authorization evidence.

The eighth #193 attempt started Query containers but their startup installer
reported `FileNotFoundException`: the adapter passed a bare container path to
Spring's web resource loader. It now matches the existing Query Kubernetes
configuration's `file:/etc/simplematch/market-reference/...` URIs. The files
remain the same ConfigMap-mounted artifact/checksum; neither JVM tuning nor
application authorization changes are required. Retain the private startup logs,
Pod/event diagnostics and FAIL/teardown in `runtime-r8/`.

The ninth #193 attempt passed initial V1 HTTP matrices and captured-boundary catch-up,
then the Block observer failed with `http.client.CannotSendRequest: Request-sent` after
a connection reset. A real loopback TCP regression reproduces that error in under one
second. Failed HTTP exchanges now close the client connection, and Block retains a
confirmed rejection of each original keep-alive socket instead of reopening it during
another replica's probes. Positive CRI exit with PID zero is distinguished from an
unverified running process during cleanup. Keep `runtime-r9/` as FAIL, even though its
owned namespace/tasks/data were removed.

After these deterministic adapter failures, further runtime attempts are gated on one
consolidated fast-contract pass and Standards/Spec re-review. Offline effective-manifest
checks cover required resources, keys, mounts and artifact URIs; preflight also pins the
negative rollback image and bounds external command lifetimes. Namespace creation records
the returned UID directly; an ambiguous creation may recover positive ownership for
cleanup only. Image reuse fingerprints include the wrapper's normalization recipe.
These checks prevent known defects before applying workloads; they are not replacements
for real HTTP, multi-replica, revocation, recovery and teardown evidence.
The ensuing Standards re-review also found that an ambiguous `ctr run --rm` launch can
leave raw containerd work before namespace ownership exists. Probe node/IDs are now
persisted before launch and labelled with the run identity. Bounded exact-owned cleanup
verifies task/container/snapshot absence independently of namespace cleanup; uncertain
ownership retains FAIL. Fast timeout/orphan-snapshot/foreign-owner contracts cover this
path, and preflight refuses surviving probe identities. No new runtime failure was needed
to discover or correct this defect.

The second #164 recovery run completed the owner replacement and protocol/business
observations but the strict verifier rejected `FIX.4.4 `: PostgreSQL had padded
the existing `CHAR(8)` BeginString during JSON serialization. The read-only SQL
now casts only that field to `text`; schema, session contents and verifier
conditions are unchanged. PostgreSQL CI diffs the actual query output against a
checked-in identity baseline using a rollback fixture that also preserves a
significant trailing space in `VARCHAR`. Preserve
`out/certification/issue-164-gateway-recovery-20260827-r2/verdict.json` as FAIL with
successful restoration, not corrected deployed PASS evidence.

A retained #164 deployment was also ineligible after the configured local 23:59
automatic close: all 15 Risk session barriers were closed and Matching was no
longer Ready for trading. Preserve the read-only diagnostic in
`out/certification/issue-164-r4-closed-precondition-20261008`; no Gateway fault was
injected. Start a fresh source-aligned disposable deployment in an eligible time
window rather than changing the clock, reopening closed barriers or disabling
automatic close. A historical trading artifact does not bypass the real local
operations schedule.

The #164 remote-delivery contracts failed on 2026-10-08 because the CDC input
manifest test assumed a locally built native validator existed in a clean CI
checkout. The CDC assertions now belong to
`scripts/test-local-certification-artifact-fingerprint.sh`, which already owns
an isolated repository and validator fixture; the live observer still checks
and executes the real validator. Before delivery, run the workflow's contract
commands from a clean checkout without generated native build output. Preserve
the [original failed CI](https://github.com/WenHsuanYu/SimpleMatch/actions/runs/37720241668)
as a test-fixture failure, not a deployed CDC failure.

The first #168 remote lifecycle-contract run stopped with exit 127 because
the new adapter test used optional `rg` to check a fixed error-message string.
The runner did not provide ripgrep. Use standard `grep -Fq` for that literal
assertion; do not add a CI package dependency or weaken the conflicting-option
check. Run the adapter test with a tool PATH that excludes `rg` before delivery,
then run the workflow's complete contracts. Preserve the
[original failed CI](https://github.com/WenHsuanYu/SimpleMatch/actions/runs/37897697115)
as test portability evidence, not a deployed Matching recovery failure.

The first #162 resting-buy run reached the real command, `ORDER_RESTED`, and durable business
effects but failed because the verifier equated the FIX Pending New `O-<ClOrdID>` WAL identity
with Risk's derived order UUID. The corrected fixture checks the actual protocol identity and
keeps account/ClOrdID correlation plus every downstream UUID equality; the fast verifier test
must pass before deployment. Preserve `out/certification/issue-162-resting-buy-20260827-r4`
as FAIL with successful restoration, never relabeling captured observations as a new run.

The first #168 Matching recovery run reached real owner replacement, a new FIX
cancel and controlled Kafka redelivery, but its verifier compared runtime
`owner_id` with the Pod name alone. Matching actually identifies each execution
as `<Pod name>:<Pod UID>` through the downward API and lease holder identity.
The safe fix binds runtime evidence to the observed replacement UID; fixtures
reject the old UID, another UID and an unqualified Pod name. Run the fast Ruby
verifier tests before deployment. The captured trace also passes the corrected
verifier in a separate offline diagnostic, not as deployed certification.
Preserve `out/certification/issue-168-matching-recovery-20260827-r1/verdict.json`
as FAIL with successful restoration; rerun against fresh source-aligned
prerequisites without modifying business data, offsets or the failed report.
This corrected verifier assumption is not a recurring runtime defect.

The 2026-10-18 current-runtime verification preparation reproduced a handoff mismatch before
deployment: the full-local report producer writes `PASSED`, but the resting-buy deployment verifier
accepted only `PASS` or `PARTIAL`. Its existing public verifier test now consumes the real report
producer's output; the original red result was `deployment completion does not match the resting-buy
contract`. The fix accepts `PASSED` while preserving required phase, manifest, fingerprint and source
checks. Negative fixtures reject failed, unknown, empty and duplicate statuses, plus missing/failed
dependencies under both `PASSED` and `PARTIAL`. Run these regressions before dependent business
verification. This is a corrected verifier vocabulary mismatch, not a deployed trading failure.

The 2026-10-18 complete run stopped at `kubernetes-migrations`: all eight retained failed Flyway
Pod logs recovered on 2026-10-19 show `BUILD FAILED` and Maven Central `UnknownHostException`
before the Job deadline. PostgreSQL init probes accepted connections. The image seeded only the
Gradle wrapper, so every fresh Pod still needed external build/plugin dependencies before SQL could
run. Preserve `out/certification/current-runtime-20261018-r1` as FAILED and the recovered logs in
`out/verification/current-runtime-20261019/preflight/all-flyway-logs`; the later host reboot did not
cause the original failure. Current DNS success does not establish its historical availability.
Prepare the complete migration dependency seed during image build and require the real entrypoint
to migrate and repeat safely from a new writable cache with external repository access disabled.
Missing seed must fail closed. Verify the existing owner/schema variants against disposable
PostgreSQL before another full run; changing SQL, deadlines or network policy cannot substitute for
this check. This dependency-packaging defect belongs to the image's deterministic validation.
The corrected image passed all six owners twice against fresh PostgreSQL with a read-only root,
new writable caches, a 2 GiB container limit and external access disabled. The 39 versioned
migrations, unchanged repeat histories and shared-schema contracts passed; retain the diagnostic
results in `out/verification/current-runtime-20261019/flyway-image/runtime-r2` separately from the
subsequent complete deployment certification.

The same preparation also found that CRI normalizes short image references while containerd
execution needs the exact observed reference, and that a repository-prefix filter can include a
shell-less application image among helper probes. Select helper/dependency images from the
canonical inventory, resolve their observed containerd references, then execute each pinned helper
on every eligible worker. Preserve those initial probe diagnostics separately from runtime image
corruption; the corrected helper probes passed before the original deployment.

The first dependent Query attempt after the 2026-10-19 aggregate stopped before releasing its
public FIX order because the system observer read the old
`/var/lib/simplematch/matching/runtime-metrics.json` path. All 15 deployed Matching containers
explicitly configured `MATCHING_METRICS_PATH` under `/var/run/simplematch/matching/`; those files
were fresh and reported Ready/Open. The deployment had moved runtime observations to a shared
temporary volume, but four certification reads still used the former persistent-volume path.
Resolve the path from the observed Matching Pod configuration through the shared reader contract;
reject missing, duplicate or unsupported paths rather than falling back to a possibly stale PVC
file. Regression checks must exercise the actual capture adapter, including both explicitly
configured supported paths. Preserve `out/verification/current-runtime-20261019/gates/query` as
FAIL with successful cleanup. This deterministic observer defect is separate from the preceding
host OOM event and belongs to the reader's source validation.

The 2026-10-20 Query attempt reached Risk acceptance and a resting Matching order, but its
prepared FIX client closed after Pending New, before the asynchronous final delivery. Keep
`out/verification/current-runtime-20261020/r4/gates/query/matching-fixture/buy/verdict.json` as FAIL;
the parent aggregate's 62 PASS results remain a separate claim. Positive certification clients
must retain their session until the runner observes the correlated durable SENT intent and the
client receives that exact ExecID. Preserve receipt-only fault scenarios and their recovery
protocols. Before the next deployment, require a real FIX regression with delayed final delivery,
an unrelated ExecID, and the original receipt-only behavior, plus adapter checks rejecting PENDING
or unrelated completion evidence. Sequential submissions to the same Gateway CLIENT identity
must retain initiator sequence state; cleanup may remove only runner-owned temporary stores.
Never reset session ledgers, mark intents SENT manually, or extend timeouts to mask this defect.
Query EXIT reporting must retain the root evidence directory even while a fixture changes its
working evidence scope. This is a corrected certification-client defect, not a recurring platform
failure.

Before dependent scenarios, verify that the retained trading window is still OPEN. The earlier
aggregate's runtime had automatically closed at 23:59 while execution was interrupted; its PASS
evidence was preserved, and a fresh run used the approved artifact's original trading day without
changing the host clock or reopening a closed barrier. Before that fresh deployment, an idle
Desktop VM still retained most host memory after the owned namespace and PVC cleanup. Only after
confirming no application namespace/PV remained, the same Desktop service was restarted once;
the daemon identity, canonical four nodes, registry, 21 volumes and 40 images were preserved.
Repeat control-plane stability and all 15 helper execution probes after such a restart, and record
host available memory and swap before starting another resource-intensive run.

The 2026-10-20 R5 Query scenario received the actual final IOC cancellation and completed
Risk, Matching, Account and projection processing, but its strict verdict correctly failed:
the active-evidence producer copied the initial PendingNew receipt with empty terminal fields,
and restoration compared an absent `riskQuarantines` field with zero. Preserve the original
`out/verification/current-runtime-20261020/r5/gates/query/verdict.json` as FAIL. Bind the real
completed FIX report to the original receipt and the exact SENT delivery intent before copying
terminal metadata; retain the initial receipt identity. Consume the existing
`critical_consumer_state_is_healthy` contract for restoration and explicitly propagate observer
failures when the producer is called in an OR-list. Before deployment, run the actual producers
through the unchanged nine-check verdict, including unrelated or incomplete completions, missing
required health fields, pending delivery and observer nonzero exit. A corrected producer replay
of retained data is diagnostic evidence only; it does not replace a source-aligned deployed run.

The same run's Market Data and Streamer scenarios exited zero, but immediate external binding
snapshots observed both new Ready Pods and old terminating Pods after restoration. Keep those
failed snapshots, wait for the observed terminating Pods to disappear within a bounded window,
then capture a new snapshot using the unchanged replica, ownership and image checks. Do not
rerun a successful scenario or relax replica validation to conceal an observer timing error.
The Streamer replacement acknowledgement intentionally has a zero-byte marker file; an independent
audit must check that explicit signal exists while still checking nonempty client reports,
actual two-snapshot recovery, group assignments and replacement timing. Preserve the original
failed audit separately from its corrected read-only audit.

## Recurring lessons

| ID | Symptom | Root cause | Prevention check | Safe fix | Last verified |
| --- | --- | --- | --- | --- | --- |
| DT-002 | Kubernetes commands fail, or the API refuses connections | Docker is stopped, the kind cluster is absent, or the active context is not canonical | Run `docker info`, verify `kind-simplematch-live`, verify the current context, and verify one control plane plus three labelled workers before deployment work | Restore the daemon or select the verified canonical context in a run-scoped `KUBECONFIG`, preserving the user's default context; do not recreate or delete resources during a failed preflight | 2026-10-09 (redacted: #168 continuation found inactive Desktop and refused kind API, restarted the same daemon and verified the unchanged canonical topology; the user's default context remained `docker-desktop`. The interrupted r2 run could not resume its completed FORBID open-barrier phase, so r3 used fresh prerequisites.) |
| DT-005 | Native configure or image build is killed by the host | Parallel compilation exceeds the local memory budget | Check available Docker/host memory and use the documented bounded parallelism for the selected preset before starting the build | Lower build parallelism or adjust the local resource budget; do not misclassify exit 137 as a source failure | 2026-08-15 |
| DT-010 | PostgreSQL and dependent workloads enter CrashLoopBackOff after Docker/kind recovery, or Docker Desktop metrics temporarily report zero CPUs | Docker Desktop retained a virtual-disk limit larger than the relocated host filesystem, then its sparse `Docker.raw` consumed all user-available ext4 blocks; local-path PVC requests are logical reservations rather than preallocated capacity | Before production-like work, require at least 40 GiB of host-usable space and require the Desktop disk limit to remain at or below 75% of its host filesystem; keep the existing worker `/var` and PVC-envelope checks | Inventory exact owners before cleanup; reclaim only unreferenced resources, and recreate or resize the Desktop disk only with explicit acknowledgement that shrinking discards its containers, images, volumes, and kind clusters | 2026-09-16 (redacted: Docker Desktop settings log and host filesystem measurements) |
| DT-012 | A newly created kind control plane never becomes healthy and kubelet reports `overlay ... invalid argument` while creating Pod sandboxes | Docker's relocated data root was on an NTFS filesystem; images remained readable, but nested containerd overlay mounts used by kind could not be created reliably | Before creating kind, verify `docker info` storage root and `findmnt -T <DockerRootDir>`; require a Linux filesystem such as ext4 for the Docker data root and verify a disposable nested container before deployment | Move Docker data to a Docker Desktop-supported Linux VM disk or Linux filesystem, restart the daemon, delete only the failed canonical cluster, and rerun the repository cluster preflight | 2026-08-15 |
| DT-014 | A kind helper Pod reports `/bin/sh: exec format error` after Docker data relocation | The relocated Docker image store retained a platform-resolution state that was not usable by the nested kind/containerd runtime, even though the image metadata appeared to be amd64 | Before deployment, verify the helper image can execute a minimal `/bin/sh -c true` on every canonical worker and verify the exact local image is exportable as `linux/amd64` | Rebuild or flatten only the exact helper image from its verified amd64 child manifest, load it into the canonical cluster, and rerun the affected phase; do not reset the Docker store or prune the cluster | 2026-08-15 |
| DT-017 | BootBuildImage fails with a run-image platform/export error after Docker data relocation | A multi-platform Paketo tag resolved through an image index that the relocated local store could not export for the requested amd64 platform | Before BootBuildImage, verify host architecture, local run-image OS/architecture, and `docker image save --platform linux/amd64` for the exact reference | Use the verified amd64 local recovery reference or flattened image with `IF_NOT_PRESENT`; keep the canonical registry reference unchanged and never use broad Docker prune as repair | 2026-08-15 |
| DT-025 | A retained KRaft topic-provisioning Job times out after a broker sandbox/runtime recreation, while the affected broker remains in CrashLoopBackOff with `UnknownHostException` for peer headless-service names | The broker-side runtime was recreated while the Kafka quorum was active; the affected JVM retained a negative peer-DNS result even after EndpointSlice records and `getent` recovered, so provisioning could create early topics but could not complete the retained topic set | Before provisioning and after any node/runtime disruption, resolve each `kafka-{0,1,2}.kafka-headless` name from a broker-side probe and require all three broker startup/readiness probes to pass continuously | Restart only the affected broker Pod after peer DNS is confirmed, preserving its retained PVC; if the scoped restart does not converge, stop and rebuild the canonical cluster through `manage-simplematch-live.sh` rather than changing Kafka data or offsets | 2026-08-31 (redacted: `out/certification/fresh-20260828-0827/kafka-failure/one-broker-loss/matching.commands.topic.txt`) |
| DT-028 | A fresh production-like run times out at `kafka-connect` rollout after five minutes, while the connector Pods later become Ready without restarts | The first kind run had to pull and unpack the pinned roughly 1 GB Debezium image from Quay; the repository's 300-second rollout wait expired before the cold pull completed, so the timeout was an image-cache/startup-window failure rather than a connector application failure | Before applying workloads, verify the exact pinned Debezium image is available and executable on every eligible kind worker, and record whether the run is cold-cache or warm-cache; treat a cold pull that exceeds the rollout window as a precondition failure | Let the exact pinned image pull complete or pre-pull it through the verified worker runtime, then rerun the retained run without changing Kafka data, offsets, or connector semantics; preserve the timeout diagnostics | 2026-08-31 (redacted: `out/certification/issue-137-query-20260831-desktop-r1/logs/kubernetes-risk-outbox-connector.log`) |
| DT-029 | A clean Desktop kind cluster repeatedly places kube-controller-manager and kube-scheduler in CrashLoopBackOff, or namespace cleanup remains pending, while the application namespace is empty | Docker Desktop VM I/O stalls etcd's linearizable reads and lease transactions; the control-plane components lose their leases and fail their local probes even though etcd may later report Ready, so this is an environment stability failure rather than an application workload result | Before applying deployment resources or starting cleanup, require controller-manager, scheduler, and etcd to be Ready with unchanged restart counts across a bounded stability window, verify `/readyz?verbose`, and inspect recent control-plane events for lease timeouts or probe failures | Preserve the control-plane and etcd diagnostics, allow a bounded recovery, then rebuild only the canonical cluster through `manage-simplematch-live.sh` when the failure repeats; do not force-finalize namespaces, edit Kafka data, or reinterpret a run started during the unstable window | 2026-09-14 (redacted: Docker Desktop backend IPC `/ping` repeatedly timed out; controlled restart could not stop the VM) |
| DT-034 | A source-aligned production-like run stops during the Matching image build with `rpc error: code = Unavailable desc = error reading from server: EOF` | Docker Desktop's BuildKit server was shut down while the vcpkg/Protobuf build was streaming; the application build had not produced a source or test failure | Before a long image build, require `docker info` and the selected BuildKit builder to be healthy, and keep Docker Desktop running until the command exits; classify a daemon shutdown as an environment precondition failure | Reopen Docker Desktop, wait for the Docker API and builder health checks to pass, then rerun the complete source-aligned run with a new evidence directory; do not promote partial build output to deployment evidence or alter source/build semantics | 2026-09-01 (redacted: `out/certification/issue-137-query-20260901-prod-final4/logs/local-image-build/matching.log`, Docker Desktop build log) |
| DT-036 | Risk-service and QuickFIX Flyway Jobs are first recorded as `OOMKilled` under the local 2 GiB container limit, then succeed on the Job retry with the same image | A cold Gradle/Kotlin process can exceed the container cgroup peak even with a 1 GiB heap and in-process compiler; the sequential migration boundary prevents application contention but did not bound Gradle task workers | Rendered local Flyway Jobs must expose the bounded JVM, in-process compiler, and a positive `SIMPLEMATCH_GRADLE_MAX_WORKERS` value; inspect every failed Job Pod termination reason before publishing a migration PASS | Pass `--max-workers=1` through the shared Flyway runner and keep the existing backoff/complete-job proof; never treat an OOM retry as a source or schema failure | 2026-09-01 (redacted: retained namespace `simplematch-local-cert-20260901-063222-16645`, Pods `risk-service-flyway-fl2qj` and `quickfix-gateway-flyway-58462`) |
| DT-037 | Matching owner `matching-0` exits during startup with `unable to query Matching Kafka committed offset: NOT_COORDINATOR` and is restarted before becoming Ready | The broker had accepted the topic/barrier setup but its group coordinator was not yet stable when the direct-assignment consumer queried committed offsets; the current Matching startup treats that transient metadata error as fatal | Before applying Matching, verify all Kafka brokers and headless peer names are Ready, then inspect every Matching Pod's restart count and previous log for coordinator errors; keep the startup result distinct from a later Ready state | Preserve the failed-Pod evidence, allow the bounded Kubernetes restart after Kafka metadata converges, and do not edit offsets or data; a client-side retry policy remains a separate follow-up if the race repeats | 2026-09-01 (redacted: retained namespace `simplematch-local-cert-20260901-063222-16645`, `matching-0 --previous`) |
| DT-038 | A clean production-like run times out while waiting for `matching-fixture-publisher`, with only a `Pulling image` event; the Pod later becomes Ready/Completed with the same immutable digest | A cold kind worker had not yet cached the native Matching image, and the fixture readiness wait was 120 seconds although image unpacking could take longer; no registry digest, platform, snapshot, or execution failure was observed | Before deployment, verify the exact Matching digest is executable through each eligible worker's containerd; retain a bounded 600-second fixture wait for first-use pulls and record whether the cache is cold | Let the exact digest pull complete or warm only that digest in the affected workers, then rerun the source-aligned phase; preserve the original timeout evidence and never change Kafka data, offsets, or image identity | 2026-09-16 (redacted: `out/certification/issues-158-159-20260916-r1/logs/kubernetes-open-barriers.log`, worker containerd image/execution probes) |
| DT-039 | BootBuildImage stops before pulling the public Paketo builder with `'username' must not be null` | The Docker credential helper supplied no usable credential, and Spring Boot 4.1.0 fell back to an empty Docker Hub `auths` entry, where it requires a username/password | Inspect credential-field presence only, never credential values; before a public-image build, verify the selected configuration can fetch registry metadata and still resolves to the intended daemon | Use a run-scoped anonymous `DOCKER_CONFIG` only for public images, retaining the original context metadata and verifying the daemon identity matches; leave the user's login configuration unchanged and preserve the failed report | 2026-10-05 (redacted: `out/certification/issue-160-images-20261005-r2/logs/account-image-stacktrace.log`; isolated configuration pulled the same public builder successfully) |
| DT-040 | Java workloads enter ImagePullBackOff or execution probes find absent preloaded images | Kubelet image GC reached its separate 80% high threshold on Docker Desktop's shared VM image filesystem at 84% in #162 and 82% in #168 preload work; six concurrent migration Pods later raised #168 r2 to 84% and r3 to 81%, again removing unused source/helper images. Host filesystem headroom did not measure VM image headroom | Measure worker `/var/lib/containerd` filesystem usage before image builds, image load and workload apply, inspect the configured GC thresholds, and inventory exact references if approaching the high threshold; include image-build and concurrent migration temporary-data peaks, not only the initial preload measurement. Node Ready/DiskPressure alone is not an image-GC headroom check. Require current digest checks and execution probes after cleanup or reload | Preserve GC diagnostics; reload only the same verified immutable image identities into affected workers and allow normal workload retry. A completed migration phase may release its exact Succeeded, exit-zero, run-owned temporary Pods after saving Pod/Job owner UIDs and completion evidence; retain the completed Jobs and PostgreSQL data. After the run, remove exact unused build caches, disposable cache volumes and obsolete import references through supported reference-safe APIs, then recheck usage and execution; never delete runtime directories or prune the canonical cluster | 2026-10-19 (redacted: `out/verification/current-runtime-20261019/runtime-probes-after-build/` records 81% imagefs and kubelet deletion of all five dependency/helper preloads on all three workers after preparatory image builds; restore their previously verified immutable targets and repeat execution probes before deployment. The earlier 2026-10-09 evidence remains `out/certification/issue-168-preflight-20261009/preflight-notes.md`, `r3-migration-image-gc.log`, completed-Pod ownership/completion snapshots and exact cleanup logs; unchanged source/dependency identities were restored and 45 new worker execution probes passed before the actual recovery test. Earlier #162 diagnostics remain in `out/certification/issue-162-image-filesystem-cleanup-20261007/`) |
| DT-041 | Docker Desktop stops and the kind API refuses connections after a retained aggregate has completed | The host kernel exhausted memory and swap and selected Desktop's QEMU process for a global OOM kill; the VM memory cap nearly equalled all host RAM while substantial browser processes also existed. The event does not identify a particular guest workload or a memory leak | Check host `MemAvailable`, swap availability and memory pressure separately from guest capacity before and during deployment gates; keep gates sequential and avoid concurrent builds or other heavy host work. VM sizing must reserve the measured non-VM host working set, and any future cap change must also recheck the full-profile request budget | Preserve the completed aggregate and later environment failure separately; restore the same daemon, verify canonical topology, immutable image execution and current scenario prerequisites before continuing. Do not attribute host OOM to Flyway container retries or weaken business gates | 2026-10-19 (redacted: `out/verification/current-runtime-20261019/desktop-oom/` records the 14:46 kernel global OOM with no free swap, after the 13:21 aggregate PASS; same-daemon recovery and 15 fresh worker probes are retained separately) |

## How to add a lesson

Add a row only when the failure is repeatable or materially risky. Each row must state:

- the observable symptom;
- the concrete root cause, separated from unrelated defects;
- a preflight check that can catch it before deployment or fault injection;
- a safe fix that preserves authoritative data and fail-closed behavior;
- the date and a redacted evidence source.

Do not put credentials, bearer tokens, Secret values, raw FIX payloads, complete account payloads, or
unredacted environment variables in this file or its evidence links. Review this table when changing
deployment scripts, certification profiles, image/build workflows, or cleanup behavior, and remove
or revise entries when the prevention check no longer matches the implementation.

## Disk hygiene boundary

Disk cleanup is an inventory operation, not an availability shortcut:

- Preserve running containers, the canonical kind cluster, images loaded into it, active Compose
  volumes, and caches required by a running build or test.
- Compare image IDs/content, not tags alone. If duplicate tags point to one image and no active
  resource references the older tag, retain the newest tag and remove the stale alias.
- Treat generated `out/`, `build/`, `vcpkg_installed/`, Gradle, and Docker builder cache as
  disposable only after confirming the current or next requested workflow does not rely on them.
- After cleanup, record what was removed, what was retained, and the measured space change.

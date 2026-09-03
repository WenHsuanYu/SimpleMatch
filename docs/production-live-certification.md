# Local and Production Certification Runbook

This document records the repeatable verification path for the Phase 1 Trading Release. The
repository-owned local production-like gate is the current completion boundary; the staging and
production sequence remains a separately rendered promotion template. A local pass is never
reported as an external production certification.

## Target and hard boundaries

The production target is:

- one externally managed Kafka cluster with three brokers;
- matching.commands and matching.events with 15 partitions, replication factor 3, minimum
  ISR 2, automatic topic creation disabled, and unclean leader election disabled;
- one Matching StatefulSet with ordinals matching-0 through matching-14, where ordinal N owns
  Kafka partition N;
- 15 schedulable nodes for the required Matching pod anti-affinity, three dedicated CPUs per pod,
  a compatible ReadWriteOncePod CSI driver, and pre-created Kubernetes Leases;
- PostgreSQL with TLS, wal_level=logical, service-local Flyway schemas, and the durable tables
  required by the current repository; and
- one production QuickFIX Gateway owner with a real FIX 4.4 counterparty/session configuration.

Kafka and PostgreSQL are external production dependencies in this repository. This runbook does
not create a broker cluster, a PostgreSQL server, a Kubernetes cluster, or a FIX counterparty.
The repository contains fail-closed provisioning/verification tools and deployment contracts; an
operator with access to those systems must supply the environment-specific values.

The following commands are read-only unless explicitly marked as provisioning. Do not run
scripts/run-flyway-ci-checks.sh against production: it drops and recreates a database.

## Local production-like certification

The local gate runs with repository-built images and disposable infrastructure. It does not push
images to GHCR or Docker Hub and does not require real staging/production registry digests,
endpoints, CIDRs, credentials, or an external FIX counterparty.

The authoritative entrypoint is:

~~~bash
bash scripts/run-local-production-like-certification.sh
~~~

The local profile uses the current local deployment image set, a three-broker Kafka cluster with 15
Matching partitions and replication factor 3, PostgreSQL, Redis, Debezium/Kafka Connect, and a
disposable Kubernetes runtime for the 15 logical Matching owners. It verifies the Risk-to-Matching-
to-consumer path, Flyway ownership, Lease/PVC behavior, restart/replay, and the relevant outage
scenarios. It may run the dependency profile with Compose and the Kubernetes profile with kind; the
runner owns only its named project, namespace, cluster, volumes, and evidence directory.

The local Kafka capacity check keeps the production-shaped durability settings (RF3, minimum ISR 2,
`acks=all`, 30-day retention, and 30% headroom) but uses the bounded workload envelope in
`scripts/testdata/matching-topic-profile/local/capacity.properties`. That envelope is deliberately
local side-project evidence, not a production throughput or capacity claim. A different workload
envelope must be supplied explicitly through `SIMPLEMATCH_KAFKA_CAPACITY_WORKLOAD_FILE` and recorded
with the run evidence; the 1,000,000-record/day fixture remains available for the stricter profile
contract tests.

The local image digest is recorded in the evidence report as a local image identity. It is not a
promotion identity and must not replace the staging/production digest placeholders.

The Kubernetes startup dependency investigation, including the read-only Flyway runtime fixes,
diagnostic evidence, and migration-first apply sequence, is recorded in
[Local production-like Kubernetes workload startup](local-production-like-kubernetes-workload-startup.md).

The local dependency/build versions are checked in the deployment documentation and contract test:
Gradle 9.7.0, Spring Boot 4.1.0, vcpkg 2026.07.29, Apache Kafka 4.3.1, PostgreSQL 18.4, Redis
8.8.1-alpine, Debezium 3.6.0.Final, and Ubuntu 26.04 LTS for the native image builder/runtime.
These are the latest stable versions verified on 2026-08-12 for this local compatibility profile;
the contract intentionally avoids mutable `latest` and prerelease tags.

## External template acceptance criteria

The later external certification template is complete only when all of these are evidenced for the same approved
trading day and trading session:

1. The final Market Reference artifact has a reviewed contentSha256, exact source provenance,
   and a recorded operator approval.
2. Kafka describes both Matching topics with 15 partitions, three distinct replica broker IDs
   per partition, and at least two in-sync replicas for every partition.
3. The broker effective configuration disables automatic topic creation and unclean leader
   election; the Kafka client uses the production TLS/SASL command properties.
4. All 15 Matching pods are Ready, use real digest-pinned images, hold the matching Lease for
   their own ordinal, use Bound ReadWriteOncePod PVCs, and run on 15 distinct nodes.
5. Matching consumes an Open Barrier for the approved artifact, replays its assigned partition,
   and becomes Ready without an artifact/session/image-digest mismatch.
6. PostgreSQL is a primary TLS connection with logical WAL, all current service-local schemas have
   successful Flyway histories and required tables, and read-only Flyway/query-plan checks pass.
7. The external QuickFIX initiator logs on to the Gateway and receives an ExecutionReport for a
   designated test order. By default the order must be admitted rather than rejected.
8. Evidence includes command output, artifact checksum, Kubernetes context/namespace, Kafka
   cluster/topic description, PostgreSQL endpoint identity without the password, FIX session IDs,
   test order identity, and rollback/cleanup decisions.

## Verification already completed or reconfirmed

The table combines evidence recorded on 2026-08-12 with native checks reconfirmed on 2026-08-14;
rows that were not rerun retain their original date and boundary.

| Layer | Command or scenario | Result and boundary |
| --- | --- | --- |
| Native build | cmake --build --preset full-native-dev --parallel | Passed |
| Native full feature tests | ctest --preset full-native-dev --output-on-failure | 75/75 passed from the current full-native build tree on 2026-08-16 |
| Native reduced feature tests | cmake --build --preset dev-debug --parallel; ctest --preset dev-debug --output-on-failure | 38/38 passed in the historical 2026-08-12 run; not rerun after #127 changes |
| Native capacity harness | scripts/run-matching-capacity-certification.sh | Repository gate available; report is non-certifying until run on pinned production-shaped hardware |
| Kubernetes manifest contract | bash scripts/test-matching-kubernetes-manifests.sh | Passed; static contract only |
| Kafka profile fixtures | bash scripts/test-matching-topic-profile.sh | Passed; includes RF/ISR/safety and duplicate-replica rejection |
| Outbox contracts | bash scripts/verify-outbox-connector-contracts.sh; bash scripts/run-outbox-cdc-contract-check.sh | Passed in the disposable CDC environment |
| Java services | Persistence, Account, QuickFIX, and Market Data module tests | Passed in the controlled Gradle environment |
| Java quality gate | GRADLE_USER_HOME=/tmp/simplematch-gradle-cache ./gradlew --no-daemon -q staticAnalysis | Passed after the current Java changes |
| Local production-like gate | `SIMPLEMATCH_CERTIFICATION_TRADING_DAY=2026-08-11 SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST=tools/market-reference-builder/data/2026-08-11/delivery/manifest.yaml bash scripts/run-local-production-like-certification.sh --skip-build --skip-compose` | Fresh 2026-08-15 canonical three-worker Kubernetes run passed static contracts, image load, ordered Flyway migrations, workload rollout, Risk outbox connector, Open Barriers, and the 15-pod Matching fleet. The report is intentionally `PARTIAL` because build and Compose runtime phases were skipped; it is not a Compose or full local certification claim. |
| Market-data streamer and operational adapters | Focused service tests and Kubernetes overlay contract | Passed structural/runtime adapter checks; gRPC subscriber, live Gateway collectors, and projection replay remain capability-specific evidence |
| Repo-local FIX certification | :services:quickfix-gateway:certificationTest | Passed; real in-process QuickFIX/J acceptor/initiator, H2, WAL, duplicate/cancel/recovery scenarios |
| Disposable kind Matching smoke | One native matching-0 against one in-cluster broker | Lease/PVC/replay/Ready path passed; one node and RF1, therefore non-certifying |
| Disposable kind restart | Delete/recreate one Matching pod normally | Old Lease blocked handover until expiry; new UID replayed baseline; no duplicate output |
| Canonical three-worker Matching E2E | `bash scripts/run-matching-e2e-certification.sh --fault-mode pod-delete`, `--fault-mode process-crash`, and `--fault-mode worker-stop` | Fresh local runs passed with zero loss/duplicates. The worker-stop case observed `simplematch-live-worker2` become NotReady, restarted the same container, retained the same Pod UID/Node/PVC/PV, completed replacement in 76.956s, and reached replay catch-up in 3.542s. Evidence: `out/certification/local-production-like/worker-stop-prep-20260816-r3/worker-stop-e2e-r2/`. The claim is temporary same-worker recovery, not PVC loss, cross-node takeover, 24-hour endurance, or production certification. |
| Canonical three-broker Kafka replacement | Delete only `kafka-0`, run the real E2E helper during loss and after rejoin | Passed: two brokers remained usable for an 8-command/8-event batch with zero loss/duplicates; replacement returned on the same node and PVC/PV; all 15 partitions of both Matching topics restored ISR 0,1,2 and KRaft follower lag was zero. Evidence: `out/certification/matching-deployed/kafka-broker-replacement-20260815/`. |
| Deployed Matching collector | `bash scripts/run-matching-deployed-certification.sh --namespace <local-run-namespace>` with the fresh E2E metrics report | Fresh report passed all deployed-local gates: 15/15 fleet, 5/5/5 placement, 15 writer CPU-allowance records, native capacity benchmark, and worker-stop E2E evidence. Evidence: `out/certification/local-production-like/worker-stop-prep-20260816-r3/deployed-certification-local/report.json`. The bounded native local-day profile separately passed 10 cycles / 102,000 commands and events with deterministic checksums. These remain local bounded evidence, not a 24-hour endurance run, production latency, or production certification. |
| Gateway kind inspection | Apply Gateway resources and inspect API objects | API-level checks passed, but placeholder image caused ErrImagePull; no runtime claim |

The disposable kind scenario was intentionally small: an Open Barrier followed by one sell and one
buy command produced two matching events, committed input offset 3, and reached READY. It proves
the runtime wiring and recovery behavior, not three-broker durability, 15-pod scheduling,
PostgreSQL production connectivity, or external FIX interoperability.

## Reproducible repository gates

Run these in order from the repository root. GRADLE_USER_HOME is only needed when the normal
Gradle cache is read-only or unsuitable; it is not a production setting.

Before applying any Compose or Kubernetes resources, or injecting a deployment fault, complete the
mandatory preflight in [Deployment Test Lessons](agents/deployment-test-lessons.md). Confirm the
Docker daemon, canonical kind context and worker topology, observed runtime identities, isolated
namespace/evidence directory, and before/after resource inventory. Stop before fault injection if
any preflight check fails.

~~~bash
cmake --build --preset full-native-dev --parallel
ctest --preset full-native-dev --output-on-failure

cmake --build --preset dev-debug --parallel
ctest --preset dev-debug --output-on-failure

bash scripts/test-matching-kubernetes-manifests.sh
bash scripts/test-matching-topic-profile.sh
bash scripts/test-phase1-deployment-contracts.sh
bash scripts/test-flyway-services.sh
bash scripts/run-outbox-cdc-contract-check.sh

# The following phase is normally invoked by the full local certification runner after the
# Kubernetes workloads and retained connectors are Ready. It is shown separately for operators
# resuming a retained, lifecycle-owned namespace. Read the run id from the retained run context:
certification_evidence_dir="${SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR:-out/certification/local-production-like}"
run_context_file="$certification_evidence_dir/run-context"
certification_namespace="$(sed -n 's/^namespace=//p' "$run_context_file")"
namespace_run_id="$(sed -n 's/^run_id=//p' "$run_context_file")"
[[ -n "$certification_namespace" && -n "$namespace_run_id" ]] || {
  printf '%s\n' 'run-context must contain namespace and run_id' >&2
  exit 1
}
bash scripts/run-risk-cdc-delivery-observer-check.sh \
  --namespace "$certification_namespace" \
  --namespace-run-id "$namespace_run_id" \
  --evidence-dir "$certification_evidence_dir/cdc-delivery"

# The observer accepts only a namespace labeled disposable and managed by
# local-production-like-certification, and --namespace-run-id must match its run-id label.

./gradlew --no-daemon :services:persistence:test :services:account-service:test :services:quickfix-gateway:test :services:quickfix-gateway:certificationTest :services:market-data-projection:test

./gradlew --no-daemon :services:risk-service:test \
  --tests 'com.simplematch.riskservice.store.RiskServiceFlywayMigrationTest'

./gradlew --no-daemon -q staticAnalysis
~~~

In a restricted development environment, prefix the Gradle commands with
GRADLE_USER_HOME=/tmp/simplematch-gradle-cache.

The repository-side capacity gate is separate from the live dependency gates:

~~~bash
cmake --build --preset full-native-dev --target simplematch-matching-capacity-benchmark --parallel
SIMPLEMATCH_BENCHMARK_CPUSET='0' \
SIMPLEMATCH_REQUIRE_PINNED=true \
SIMPLEMATCH_BENCHMARK_REPORT=/secure/certification/matching-capacity.json \
bash scripts/run-matching-capacity-certification.sh \
  --warmup 100 --iterations 1000 --maximum-resting-orders 256
~~~

The benchmark exercises 150 books and checks the measured command/event stream for loss,
duplicate event identities, state checksum equality, and deterministic serialized event bytes.
Its latency samples cover the direct native core call; the report does
not include Kafka, ring wait, publication, or recovery time. The report records the benchmark
process's effective CPU affinity when the operator supplies `SIMPLEMATCH_BENCHMARK_CPUSET`. This
is evidence about the benchmark process only; it does not prove Kubernetes CPU Manager placement
or live Matching writer isolation. The operator must record the actual CPU-manager policy, cgroup
quota, governor, workload/depth/rate, and ring occupancy alongside the JSON report. A direct-core
pass therefore does not satisfy the production gate by itself. The
For this side project, the local completion profile uses the bounded local-day envelope and the
10-cycle repeated checksum run below. A later production-shaped promotion may choose a 24-hour
wall-clock soak or larger replay profile, but those are not required for the repository-owned local
completion gate. The local gate still measures Kafka end-to-end percentiles and the 60-second
lag/120-second replacement bounds.

For repository-owned deployed evidence, use the read-only collector:

~~~bash
bash scripts/run-matching-deployed-certification.sh \
  --namespace <local-run-namespace> \
  --report out/certification/matching-deployed/report.json
~~~

It records the actual Matching Pod UID/Node/image mapping, 5/5/5 placement, process CPU
allowance, cgroup quota, PVC/Lease snapshots, and the native benchmark report. It returns
`INCOMPLETE` until the same run also supplies Kafka E2E latency, ring occupancy, loss/duplicate,
replay, and replacement measurements through `SIMPLEMATCH_E2E_METRICS_FILE`. It never deletes or
modifies the cluster and does not claim external production certification.

The local native Kafka fixture publisher is:

~~~bash
out/build/full-native-dev/simplematch-matching-kafka-fixture-publisher BROKERS MATCHING_COMMANDS_TOPIC
~~~

The fixture scenario must be interpreted with the exact artifact/session identity used by the
Matching process: Open Barrier first, then the commands for its assigned partition. The expected
result is a contiguous next input offset, acknowledged output publication, and READY.

No microsecond-level production latency claim may be made from the repository benchmark or the
disposable kind smoke. The claim requires the pinned production-shaped run and the end-to-end
evidence described by #136.

## Staging/production template sequence

The following sequence is retained for a later environment promotion. It is not required to close
the local production-like inventory and must not be run with the repository placeholders unchanged.

### 1. Build and approve the final artifact

Build a D-1 candidate, review its bounded diff and partition loads, then build the final artifact
from fresh official sources on the trading day. The final output must be immutable under the
approved root:

~~~bash
./gradlew :tools:market-reference-builder:run --args='final --trading-day YYYY-MM-DD --fetch-live --previous-artifact /secure/market-reference/approved/PREVIOUS_DAY/market_reference.json --approved-root /secure/market-reference/approved --approved-by trading-operator'
~~~

Record market_reference.json, market_reference.sha256, approval-report.json, and the
generated delivery manifest. At or below 900 KiB the delivery is an immutable ConfigMap; above
that limit the operator must use the digest-pinned OCI data-image plan. Render the generated
delivery fragment into the Risk and Matching workloads before applying it. The checked-in
matching-statefulset.yaml uses a stable example volume name, while the generated artifact
manifest owns the actual approved name; applying those fragments without rendering them together
is not a valid release.

### 2. Validate and provision Kafka

First obtain the effective broker configuration and a TLS/SASL Kafka CLI properties file from the
Kafka owner. The properties file is not committed and must not be printed in logs.

~~~bash
export KAFKA_BOOTSTRAP_SERVER='kafka-1.example:9093,kafka-2.example:9093,kafka-3.example:9093'
export KAFKA_COMMAND_CONFIG='/secure/kafka/matching-client.properties'
export KAFKA_BROKER_CONFIG='/secure/kafka/effective-broker.properties'

bash scripts/validate-matching-topic-profile.sh --bootstrap-server "$KAFKA_BOOTSTRAP_SERVER" --command-config "$KAFKA_COMMAND_CONFIG" --broker-config-file "$KAFKA_BROKER_CONFIG" --profile production --certify-production
~~~

The validator queries both topics and rejects a partition whose three replica entries are not
distinct, whose ISR is below 2, or whose effective broker safety settings drift. It does not
accept the local RF1 Compose profile.

Topic creation is a controlled mutation. Only the Kafka owner should run it after reviewing the
planned change:

~~~bash
bash scripts/provision-matching-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP_SERVER" --command-config "$KAFKA_COMMAND_CONFIG" --broker-config-file "$KAFKA_BROKER_CONFIG" --profile production --certify-production
~~~

Run the validator again after provisioning. Record the two topic descriptions, effective broker
settings, broker count, ISR state, retention/capacity calculation, and the identity of the
operator who authorized topic mutation.

### 3. Apply the 15-pod Matching fleet

Do not apply matching-session-config.example.yaml unchanged. Replace its trading day, trading
session ID, and real Matching image digest; use the approved artifact delivery output and real
image digest. The deployment prerequisites are:

- Kubernetes supports the StatefulSet pod-index label;
- 15 nodes satisfy the required hostname anti-affinity and each is labelled only after CPU Manager
  static-policy certification;
- the simplematch-rwo-pod StorageClass is backed by a CSI driver supporting
  ReadWriteOncePod;
- all 15 fixed Lease objects and the scoped Lease Role/RoleBinding are applied;
- the artifact, session ConfigMap, service ConfigMaps, Secrets, and service accounts are already
  present; and
- the container registry contains the referenced immutable image digests.

Apply the fixed ownership resources before the workload:

~~~bash
kubectl -n "$SIMPLEMATCH_NAMESPACE" apply -f deploy/k8s/matching-headless-service.yaml -f deploy/k8s/matching-lease-rbac.yaml -f deploy/k8s/matching-partition-leases.yaml -f deploy/k8s/matching-pod-disruption-budget.yaml

# Apply the rendered session/artifact resources and the rendered StatefulSet.
kubectl -n "$SIMPLEMATCH_NAMESPACE" apply -f /secure/rendered/matching-production.yaml
kubectl -n "$SIMPLEMATCH_NAMESPACE" rollout status statefulset/matching --timeout=10m
~~~

Then run the strict live gate:

~~~bash
SIMPLEMATCH_NAMESPACE="$SIMPLEMATCH_NAMESPACE" bash scripts/verify-matching-fleet-live.sh
~~~

It requires exactly matching-0 through matching-14, all Ready, all real digest-pinned, one
current Lease holder per ordinal, one Bound RWOP PVC per ordinal, and 15 distinct nodes. A one-node
kind cluster must fail this gate.

For a normal restart, use a controlled deletion and wait for Lease expiry/handover. Do not use
kubectl delete pod --force --grace-period=0 as a normal Matching operation. Validate that the
replacement has the new Pod UID, acquires only its own Lease, replays its baseline, catches up to
zero lag, and does not republish already acknowledged events.

### 4. Validate PostgreSQL without mutating it

The repository now has a read-only live gate:

~~~bash
export SIMPLEMATCH_LIVE_POSTGRES_HOST='postgres.example'
export SIMPLEMATCH_LIVE_POSTGRES_PORT='5432'
export SIMPLEMATCH_LIVE_POSTGRES_USER='simplematch_certifier'
export SIMPLEMATCH_LIVE_POSTGRES_PASSWORD='provided-out-of-band'
export SIMPLEMATCH_LIVE_POSTGRES_DATABASE='simplematch'
export SIMPLEMATCH_LIVE_POSTGRES_SSLMODE='verify-full'
export SIMPLEMATCH_LIVE_POSTGRES_SSLROOTCERT='/secure/postgres/ca.pem'

bash scripts/verify-postgres-live-certification.sh
~~~

The script checks that PostgreSQL is a primary, the connection is TLS-protected, wal_level is
logical, public.flyway_schema_history is absent, each current Flyway service schema has
successful history and required smoke tables, each service's FlywayInfo and FlywayValidate
tasks pass, and the named query-plan checks use their expected indexes. It never runs migrate,
clean, baseline, repair, reset, or database creation.

This gate verifies the current Flyway owners exposed by scripts/lib/flyway-services.sh. It does
not certify the Query/Redis deployment, replay, or outage behavior; those remain a separate live
release gate even though the repository now contains the query-service implementation.

### 5. Run external QuickFIX certification

The existing certificationTest remains repo-local. The new live task is opt-in and uses a
temporary initiator FileStore/FileLog directory, so it does not persist test sequence state in the
repository:

~~~bash
export SIMPLEMATCH_LIVE_FIX_HOST='gateway.example'
export SIMPLEMATCH_LIVE_FIX_PORT='5001'
export SIMPLEMATCH_LIVE_FIX_SENDER_COMP_ID='CERTIFIER'
export SIMPLEMATCH_LIVE_FIX_TARGET_COMP_ID='SIMPLEMATCH'
export SIMPLEMATCH_LIVE_FIX_ACCOUNT_ID='0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13'
export SIMPLEMATCH_LIVE_FIX_SYMBOL='2330'
export SIMPLEMATCH_LIVE_FIX_QUANTITY='1000'
export SIMPLEMATCH_LIVE_FIX_PRICE='101.25'
export SIMPLEMATCH_LIVE_FIX_CL_ORD_ID='CERT-20260811-001'
export SIMPLEMATCH_LIVE_FIX_EXPECT_ACCEPTED='true'

bash scripts/run-quickfix-live-certification.sh
~~~

The test performs Logon, sends one NewOrderSingle (35=D), waits for an ExecutionReport (35=8),
checks the client order ID, symbol, execution identity, execution type, and order status, and
then stops the initiator. It does not claim a final trade fill merely because an admission report
was received. A final fill certification requires a funded/eligible test account, an opposing
order or approved matching fixture, and evidence from the final matching.events path.

The QuickFIX owner must confirm that the designated session, account, symbol, price, quantity, and
ClOrdID are safe for a live test. If the intended test is rejection-path only, set
SIMPLEMATCH_LIVE_FIX_EXPECT_ACCEPTED=false; the session and response still need to be
operator-approved.

### 6. Collect and retain evidence

Retain, outside the repository when sensitive:

- final artifact identity and approval report;
- rendered image references and SHA-256 digests;
- Kubernetes context, namespace, StatefulSet/PVC/Lease/pod JSON, and rollout output;
- Kafka topic descriptions, effective broker properties, command-config identity, ISR and disk
  capacity evidence;
- PostgreSQL server identity, TLS/WAL results, Flyway/query-plan output, and migration ownership;
- FIX session IDs, dictionary checksum, test ClOrdID, and sanitized ExecutionReport fields; and
- any pause/reopen decision, rollback action, or outstanding quarantine.

Do not place passwords, bearer tokens, private keys, FIX credentials, or raw production orders in
Git, ConfigMaps, test logs, or this document.

## Values required for later promotion

The local gate does not require the following values. They are retained as the environment-owned
inputs for a later staging or production promotion:

1. The Kubernetes context/namespace and permission to inspect/apply; a cluster with 15 eligible
   nodes, CPU Manager static policy, and a RWOP-compatible CSI driver.
2. Real Matching and Gateway container image references with immutable SHA-256 digests. The
   repository currently contains no Dockerfiles and intentionally retains placeholder digests.
3. Three Kafka broker bootstrap endpoints, TLS/SASL command properties, effective broker
   configuration export, and authority to create/describe the two topics.
4. PostgreSQL host/port/database, a least-privilege certification user, password delivered
   out-of-band, TLS CA/hostname details, and confirmation that the target is not a disposable
   database.
5. QuickFIX host/port, sender/target CompIDs, session sequence policy, approved FIX dictionary,
   a safe canonical Account UUID, eligible symbol, price/quantity, and a unique ClOrdID.
6. The approved final artifact/session ID and the deployment renderer output that points both Risk
   and Matching at the exact same artifact checksum.
7. A decision on whether the live order may be admitted only, or whether an opposing order and
   final execution/fill must also be certified.

Until those values and permissions are available, the correct status is “local production-like gate
passed; promotion template pending,” not “externally certified.”

# Local Production-Like Certification Runbook

This document records the Phase 1 local verification and external promotion boundaries.
Issue #202 S5 retired the separate Shell aggregate. `./gradlew verifyAll` now selects
complete-local verification. `verifyAllScope` checks source, profile, and Shell limits.
The staging and production sequence remains a separately rendered promotion template.
A local pass is never reported as an external production certification.

Use the complete-local section for the executable repository-owned workflow. The production target and
later staging/production instructions define the environment and promotion template an operator
must supply and execute separately. Selected-scenario or focused diagnostic results retain their
own scope and cannot substitute for an aggregate `full-local` PASS.

For the tested source, aggregate manifest and separately recorded public-interface/recovery
results of the replacement current baseline, read
[Current development verification](current-local-certification.md). This runbook owns the procedure;
the result record owns the dated outcome and evidence boundary.

For availability and consistency of retained historical records, read the
[2026-10-18 evidence audit](local-certification-evidence-audit.md). It distinguishes a complete
historical aggregate record from an unconfirmed run summary and does not certify the current runtime.

Issue #202 retired the separate Matching capacity/CPU-affinity wrapper and
read-only deployed-report collector after confirming that no active CI,
Gradle, or full-local phase calls either script. The older command names and
reports below describe historical source-scoped evidence, not runnable
verification entry points. Native integrity and capacity checks remain in
CTest through `./gradlew nativeTest`; deployed runtime assertions remain in
the Kubernetes verification owner. Neither replacement claims pinned-CPU
performance or comprehensive deployed latency measurement.

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

The external PostgreSQL inspection task is read-only.
`./gradlew verifyPostgres` creates disposable Testcontainers databases.
Use `./gradlew :tools:dependency-tests:postgresLiveTest` for explicit read-only external checks.
The external QuickFIX task sends a real Order. Use only an approved isolated
test account and session. Other resource operations follow their own
ownership and preflight requirements.

## Complete-local verification

Issue #202 S5 P5b removed the former Shell production-like aggregate runner,
including its 62-phase graph, Compose runtime phases, and independent PASS reporter.
The repository's single complete-local selection is:

```bash
./gradlew --no-daemon verifyAll
```

This task selects the Java, static analysis, native CTest, Flyway/dependency,
QuickFIX and run-owned Kubernetes `resting-buy` owners. Its `verifyAllScope`
preflight requires an unchanged committed source tree, native
`full-native-dev`, Kubernetes `resting-buy` and the S5 Shell limits.
The 2026-11-06 R11–R22 selected Live PASS remains historical and cannot
substitute for final-source P6. A fresh complete-local `verifyAll` on
`1a3fa704f37873acacab54415ce794171fd4456a` passed on 2026-11-08:
37m17s, 194 Gradle tasks, 81/81 Native CTest checks and dedicated
Kubernetes run `6364220d-c45a-43e2-9100-daf098e78046`. Evidence under
`out/verification/kubernetes/7458c622-11ad-4cdd-8f83-69bc519582ef/`
records `status`, `assertions`, `diagnostics` and `cleanup` as `PASS`;
the run-owned namespace was removed. This proves the selected local scope,
not production HA, independently selected cluster modes or hosted S6.

The removed aggregate's independent Compose Kafka/PostgreSQL/Redis/Connect
startup and fault phases are no longer selected. Neither are its local Kafka
profile/capacity report, 15-owner phase report, resource-budget report,
registry/kind-load publication matrix, partial profiles, or per-phase
completion inventory. Existing native, Java, dependency and Kubernetes
owners retain their specific checks only. See the
[retirement contract](local-certification-phase-dag.md) and
[S5 disposition](../plans/shell-test-reduction-s5.md) for explicit coverage.

Legacy aggregate evidence remains historical and keeps the run/source/image
identity recorded at execution. The CDC focused diagnostic requires identical
runtime provenance. P5b changed its hashed inputs and retired the aggregate
producer. Consequently pre-P5b retained runs fail closed and cannot continue
under current source. Historical or partial evidence never upgrades to S5.

The production target, external environment template, and dated baseline
records below remain separate from this local verification entry.

### Normal resting-buy integration baseline (#162)

Source `b5eff574f6adabed7843bd9494494fed959bda74` passed one real deployed BUY / LIMIT / DAY
scenario on 2026-10-07 using the approved historical trading day `2026-08-27`. The production
live collector made Gateway open-eligible; authenticated operator open and the final read-only
status both observed `OPEN`. The FIX-originated Risk admission correlated with the physical
Kafka command and Matching's `ORDER_RESTED`: 1,000 shares of `XTAI/1101` at 24.30, with zero
fills. Persistence retained `RESTING` with full leaves, and Account held one accepted reservation
for 24,300 with zero utilized notional. The three critical consumers applied the same event once,
with matching payload digests and no quarantine.

The normal result is `out/certification/issue-162-resting-buy-20260827-r5/verdict.json`;
its source-aligned deployment is `out/certification/issue-162-deployment-20260827-r5/report.md`.
All nine trading prerequisites passed. The parent report remains intentionally `PARTIAL` because
Compose was explicitly skipped; the business result is `PASS` with successful restoration, not a
filled-trade, recovery, external-production or `full-local` certification claim. The disposable
namespace and its 20 PVC/PVs were removed after verification; reports and current images remain.
Use the existing [normal-scenario recipe](../../scripts/end-to-end/critical-consumers/README.md#run-the-normal-scenario)
for reproduction. Later evidence-documentation commits do not change the certified runtime code.

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

The following table preserves historical verification dates and sources. Use the
[current certification record](current-local-certification.md) for the replacement current
baseline; these older rows do not imply that every command was rerun for its tested source.

The table combines evidence recorded on 2026-08-12 with native checks reconfirmed on 2026-08-14;
rows that were not rerun retain their original date and boundary.

| Layer | Command or scenario | Result and boundary |
| --- | --- | --- |
| Native build | cmake --build --preset full-native-dev --parallel | Passed |
| Native full feature tests | ctest --preset full-native-dev --output-on-failure | 75/75 passed from the current full-native build tree on 2026-08-16 |
| Native reduced feature tests | cmake --build --preset dev-debug --parallel; ctest --preset dev-debug --output-on-failure | 38/38 passed in the historical 2026-08-12 run; not rerun after #127 changes |
| Historical native capacity wrapper (retired by #202) | `scripts/run-matching-capacity-certification.sh` (removed) | Older source-scoped wrapper result; current native capacity/integrity coverage remains in CTest |
| Kubernetes manifest contract | [Historical manifest contract](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/scripts/test-matching-kubernetes-manifests.sh) | Passed; static contract only; retired in S4 |
| Kafka profile fixtures | [Historical profile fixtures](https://github.com/WenHsuanYu/SimpleMatch/blob/b3405a9d3c8da82d570e982eb67cf7bac5220586/scripts/test-matching-topic-profile.sh) | Passed; includes RF/ISR/safety and duplicate-replica rejection; retired in S4 |
| Outbox contracts | ./gradlew verifyCdc | Passed in the disposable CDC environment |
| Java services | Persistence, Account, QuickFIX, and Market Data module tests | Passed in the controlled Gradle environment |
| Java quality gate | GRADLE_USER_HOME=/tmp/simplematch-gradle-cache ./gradlew --no-daemon -q staticAnalysis | Passed in the historical 2026-08-14 run; current-head validation is required after the #138 CDC changes |
| Historical local production-like gate (retired) | Former `run-local-production-like-certification.sh` with `--skip-build --skip-compose` | The 2026-08-15 source-scoped run passed the recorded Kubernetes phase checks and reported `PARTIAL`. The Shell entry no longer exists. This result does not certify current source or complete-local acceptance. |
| Market-data streamer and operational adapters | Focused service tests and Kubernetes overlay contract | Passed structural/runtime adapter checks; gRPC subscriber, live Gateway collectors, and projection replay remain capability-specific evidence |
| Repo-local FIX certification | :services:quickfix-gateway:certificationTest | Passed; real in-process QuickFIX/J acceptor/initiator, H2, WAL, duplicate/cancel/recovery scenarios |
| Disposable kind Matching smoke | One native matching-0 against one in-cluster broker | Lease/PVC/replay/Ready path passed; one node and RF1, therefore non-certifying |
| Disposable kind restart | Delete/recreate one Matching pod normally | Old Lease blocked handover until expiry; new UID replayed baseline; no duplicate output |
| Historical three-worker Matching E2E (Shell runner retired in #202 S5) | Earlier standalone Pod-delete, process-crash, and worker-stop fault modes | The dated local runs passed with zero loss/duplicates. The worker-stop case observed `simplematch-live-worker2` become NotReady, restarted the same container, retained the same Pod UID/Node/PVC/PV, completed replacement in 76.956s, and reached replay catch-up in 3.542s. Evidence: `out/certification/local-production-like/worker-stop-prep-20260816-r3/worker-stop-e2e-r2/`. This historical result is temporary same-worker recovery, not PVC loss, cross-node takeover, 24-hour endurance, or production certification. |
| Canonical three-broker Kafka replacement | Delete only `kafka-0`, run the real E2E helper during loss and after rejoin | Passed: two brokers remained usable for an 8-command/8-event batch with zero loss/duplicates; replacement returned on the same node and PVC/PV; all 15 partitions of both Matching topics restored ISR 0,1,2 and KRaft follower lag was zero. Evidence: `out/certification/matching-deployed/kafka-broker-replacement-20260815/`. |
| Historical deployed Matching collector (retired by #202) | `scripts/run-matching-deployed-certification.sh` (removed) | The earlier report passed 15/15 fleet, 5/5/5 placement, 15 writer CPU-allowance records, native capacity benchmark, and worker-stop E2E evidence. Evidence: `out/certification/local-production-like/worker-stop-prep-20260816-r3/deployed-certification-local/report.json`. This is historical bounded evidence, not a current-run PASS, production latency, or production certification. |
| Gateway kind inspection | Apply Gateway resources and inspect API objects | API-level checks passed, but placeholder image caused ErrImagePull; no runtime claim |

The disposable kind scenario was intentionally small: an Open Barrier followed by one sell and one
buy command produced two matching events, committed input offset 3, and reached READY. It proves
the runtime wiring and recovery behavior, not three-broker durability, 15-pod scheduling,
PostgreSQL production connectivity, or external FIX interoperability.

## Reproducible repository gates

Run these in order from the repository root. GRADLE_USER_HOME is only needed when the normal
Gradle cache is read-only or unsuitable; it is not a production setting.

Before applying any Compose or Kubernetes resources, or injecting a deployment fault, complete the
mandatory preflight in [Deployment Test Lessons](../agents/deployment-test-lessons.md). Confirm the
Docker daemon, canonical kind context and worker topology, observed runtime identities, isolated
namespace/evidence directory, and before/after resource inventory. Stop before fault injection if
any preflight check fails.

Use `./gradlew manifestCheck` for rendered schemas and basic workload probes.
S4 retires the former Phase 1 Shell collection and its static phase.
Existing dependency and runtime entry points retain their own scope.
See the [owner-amended S4 disposition](../plans/shell-test-reduction-s4.md).

~~~bash
cmake --build --preset full-native-dev --parallel
ctest --preset full-native-dev --output-on-failure

cmake --build --preset dev-debug --parallel
ctest --preset dev-debug --output-on-failure

./gradlew manifestCheck
./gradlew -q :tools:dependency-tests:test
./gradlew verifyCdc

# The former Kubernetes Risk CDC observer is retired. See the CDC publication
# verification guide for its historical scope and the current verifyCdc owner.

./gradlew --no-daemon :services:persistence:test :services:account-service:test :services:quickfix-gateway:test :services:quickfix-gateway:certificationTest :services:market-data-projection:test

./gradlew --no-daemon :services:risk-service:test \
  --tests 'com.simplematch.riskservice.store.RiskServiceFlywayMigrationTest'

./gradlew --no-daemon -q staticAnalysis
~~~

In a restricted development environment, prefix the Gradle commands with
GRADLE_USER_HOME=/tmp/simplematch-gradle-cache.

The repository-side native integrity/capacity gate is separate from the live
dependency gates. The current entry point is:

~~~bash
./gradlew nativeTest -PnativePreset=full-native-dev
~~~

The CTest capacity benchmark exercises a fixed 150-book distribution and
checks the command/event stream for loss, duplicate identities, state
checksum equality, and deterministic serialized event bytes. Its latency
samples cover the native core, not Kafka, ring wait, publication, or
recovery time. The retired wrapper additionally captured host/CPU metadata
and an optional taskset-constrained report. Those measurements are no longer
part of S5 complete-local acceptance. A direct-core pass never establishes
Kubernetes CPU pinning or production performance.
S5 retires the separate ten-cycle local-depth loop and its aggregate report.
The existing CTest suite retains bounded 10,000-command replay and the native integrity benchmark.
`nativeTest` configures, builds, and executes that suite with a fresh JUnit report.
The [S5 disposition](../plans/shell-test-reduction-s5.md) records the intentional loss of repeated calibration.
Historical ten-cycle results remain evidence for their original source and date.
This native task does not establish Kafka latency, deployed recovery, or production endurance.

The independent deployed report collector and standalone Matching E2E runner
are retired under Issue #202. Jupiter still observes actual Matching owners,
selected recovery and terminal close. Native CTest retains bounded replay and
integrity. The selected test does not reproduce the old whole-fleet ring
occupancy, optional worker-stop recovery, benchmark/latency report or repeated
independent post-crash Kafka fixture batch.

Issue #202 selects that owner inside the existing `verifyKubernetes`
resting-buy scenario. The test retains the Pod-delete recovery.
It sends `SIGKILL` to the exact run-owned CRI task and checks original
Pod UID/node/PVC/PV, changed container, incremented restarts, exit 137,
Ready state and completed input progress. The following public FIX order
checks post-crash business delivery. The selected source-bound Kubernetes
Live run at commit `cdab22d2` passed with assertions, diagnostics and cleanup
PASS; evidence is under `out/verification/issue202-risk-cas-fixed-20261106-r1/`.
This focused result does not establish complete S5/P6 acceptance.

The former standalone Matching E2E runner included independent process-crash,
Pod-delete and worker-stop fault modes. It checked exact-owned namespace,
helper Pod cleanup, 15-Pod runtime ring metrics and separate native Kafka
batches before and after replacement. These auxiliary diagnostics were
retired after the selected Jupiter fault/recovery owner passed Live.
Its unique worker-stop NotReady/restart observation and whole-fleet ring
report were intentionally not copied to Jupiter. Neither implementation
proved SIGKILL-induced redelivery of an uncommitted Kafka record or global
exactly-once replay; those are not required for basic R22 acceptance.

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

./gradlew :tools:dependency-tests:postgresLiveTest
~~~

The Gradle test checks that PostgreSQL is a primary, the connection is TLS-protected, wal_level is
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
export SIMPLEMATCH_LIVE_FIX_USERNAME='provided-out-of-band'
export SIMPLEMATCH_LIVE_FIX_PASSWORD='provided-out-of-band'
export SIMPLEMATCH_LIVE_FIX_KEY_STORE='/secure/fix/client.p12'
export SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD='provided-out-of-band'
export SIMPLEMATCH_LIVE_FIX_TRUST_STORE='/secure/fix/trusted-servers.p12'
export SIMPLEMATCH_LIVE_FIX_TRUST_STORE_PASSWORD='provided-out-of-band'

./gradlew --no-daemon :services:quickfix-gateway:liveCertificationTest
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

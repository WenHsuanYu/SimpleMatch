# Risk and Account CDC verification

This document maps Issue #92 to retained automated evidence and records the reusable observation
Interface intended for Issue #156. The two issues keep different fault orchestration: #92 owns local
connector/Kafka interruption, while #156 owns distributed Kafka Connect worker loss and task
reassignment. They share how the CDC data path is observed and verified.

## Evidence layers

Account application integration and CDC publication integration remain separate seams. The current
Testcontainers scenario does not start Account application code, so its direct SQL outbox fixtures certify
Debezium EventRouter/Kafka mapping and recovery; they are not described as a single-path Account
application-to-Kafka E2E.

- `AccountReservationApplicationServiceTransactionTest` proves Account authority mutation and the
  `account.lifecycle` outbox row commit or roll back together. It independently fixes the existing
  Account topic, message-key, and lifecycle-event contract.
- `OrderAdmissionApplicationServiceTransactionTest` proves terminal Risk admission state and its
  outbox row share one transaction.
- `CdcConnectorContractsTest` verifies each canonical connector reads only its owner's outbox
  and preserves the key, payload, timestamp, partition, and header mappings.
  `CompiledOutboxPublisherGuardTest` rejects direct producer dependencies in compiled Risk and
  Account classes. Source formatting is outside this contract.
- `./gradlew verifyCdc` runs the shared Java contracts and real disposable PostgreSQL, Kafka,
  and Connect scenarios. It applies the owners' versioned Flyway migrations and exercises RF1 and
  RF3, connector pause/resume, broker stop/start, and actual Connect crash-induced redelivery.

Issue #202 retired the standalone Kubernetes Risk CDC observer and its Shell-only fixture
contract. `verifyCdc` does not replace the observer's deployed lag, Actuator, or cleanup checks.

## Shared CDC verification Module

`CdcPublicationVerifier`, `CdcOutboxProbe`, and `CdcPublicationEvidence` own publication,
selection, and evidence checks. The `recovery-evidence-verifier cdc` CLI exposes these checks.
Issue #202 retired the unused `scripts/lib/cdc-verifier.sh` adapter after its last scenario
caller was removed. Do not call the retired Shell functions from new verification code.

The Module hides PostgreSQL outbox-row parsing, baseline event-id capture, post-transition event-id
discovery, payload hashing, bounded Connect status polling, Kafka offset-window selection, Debezium
event lookup, exact key/header/timestamp/partition/value checks, topology validation, and Kafka
diagnostics. Removing the Module would spread that knowledge back across Compose and Kubernetes
scenario callers.

### Terms and lifecycle

These words describe different responsibilities; they are not interchangeable:

- **Contract** means an executable rule: a schema, predicate, ordering requirement, or failure
  condition that a validator can check. A JSON/YAML file is only the carrier for inputs or evidence;
  it cannot prove its own `PASS` value.
- **Module** means the cohesive implementation that owns those rules behind a small Interface.
  `CdcPublicationVerifier` retains the Java publication checks. Issue #202 retired the
  Kubernetes Connect reassignment diagnostic and its unused Shell adapter. It did not
  remove the Java publication checks.

The retired worker-loss command separated request parsing, REST tunnel ownership, live fault execution, and data evaluation.
`connect-worker-loss-cli.sh` parsed the request.
`connect-rest-tunnel.sh` owned the recoverable tunnel.
`connect-worker-loss-scenario.sh` performed the ordered operations and restored the fixture before PASSED publication.
The retired Java recovery verifier checked task identities, deletion, reassignment, and event linkage.
The shared CDC validator checks exact Kafka bytes and metadata.

The former deployment preflight captured prerequisites and invoked Java before fault injection.
Its report linked recovery observations without repeating the prerequisites.
The former tests checked command results, cleanup, and evidence through their interfaces.

The retired #156 focused contract test applied the same separation to its own test
harness.  It exercises deterministic preflight rejections through the public
`simplematch_focused_preflight` seam using copied fixture inputs, while one
runner-level rejection preserves the observer-ordering check and the valid,
unrelated-source, and verifier-drift cases continue to exercise the complete
runner path.  The expensive phase/evidence fixture is created once and each
negative case mutates only its targeted input.  This shortens local feedback
without skipping a production-like preflight or changing the evidence
contract; a passing focused diagnostic remains diagnostic-only evidence and
never becomes a complete certification PASS.
- **Seam** is a place where callers can replace an implementation without editing the Module.
  The retired Shell adapter accepted `CDC_OUTBOX_EXEC`, `CDC_KAFKA_EXEC`, and
  `CDC_CONNECT_STATUS_EXEC` as its command seams. These environment variables are not
  active interfaces in the current verification workflow.
- **Adapter** implements an Interface at a seam. The retired Kubernetes runner used
  `postgres_exec`, `kafka_exec`, and `connect_status` to access the external systems.
  Those commands did not decide whether publication evidence passed.
- **Runner** orders scenario setup, faults, evidence collection, and cleanup. The old
  Shell runner no longer owns a current acceptance requirement.

Retain a Contract and its Module while they own current requirements. Retire a runner only
after checking its callers and its unique verification responsibility. Preserve historical
reports as incident records. Do not use those reports as current-source acceptance evidence.

The retired Shell adapter injected three dependencies:

- `CDC_OUTBOX_EXEC` executes the Module-owned PostgreSQL query.
- `CDC_CONNECT_STATUS_EXEC` returns one connector status document.
- `CDC_KAFKA_EXEC` executes Kafka CLI reads.

The #156 Kubernetes runner and these collector adapters are retired.
Run the retained Java CDC publication contract directly:

```bash
./gradlew :tools:recovery-evidence-verifier:test --tests '*CdcPublicationCommandsTest'
```

The former Shell forwarding entry has no independent checks.
The new Testcontainers scenario observes JDBC, Kafka, and REST interfaces.
It calls the same Java verifier. The adapters supply observations from each environment.
The verifier does not manage Compose, Pods, worker selection, task reassignment, or namespaces.

## CdcProbeIdentity

The retired `cdc_read_outbox_probe` Shell function produced a temporary JSON observation called a
`CdcProbeIdentity`. It is infrastructure test state, not a domain object and not a production
contract. For a fresh aggregate, callers can use the four-argument form and identify a durable
outbox row by aggregate identity. When an aggregate may already have lifecycle history, callers first
run `cdc_capture_outbox_baseline` and pass its file as the optional fifth argument. The Module then
excludes every event id present in that baseline and requires exactly one post-transition row; zero or
multiple candidates fail closed. For Account reservation publication the locator remains
`account_reservation + reservation_id`, while event selection stays inside the Module.

An outbox baseline is a small JSON document containing the schema, aggregate identity, schema version,
and event-id set that existed before the transition. It contains no payload bytes or business values,
so it can be retained as evidence without expanding the sensitive-data surface. A concurrent extra
lifecycle event is intentionally reported as ambiguity instead of silently selecting the first row.

The Module then discovers the internally generated outbox `event_id` and records:

- event id and business identity,
- Account reservation/account identity where applicable,
- message key and expected topic,
- exact payload bytes plus SHA-256 for safe diagnostics,
- creation timestamp and expected business header,
- optional explicit partition.

The sensitive payload bytes remain inside the temporary probe so exact comparison is possible, but
failure messages never print the raw payload or its complete hexadecimal representation. Payload
mismatches report event identity, topic, partition/offset, SHA-256 values, and byte lengths.

## Exact publication and fault scenarios

The former live transport checks used the retired `cdc_assert_probe_publication` Shell function.
The current Java `verifyCdc` owner checks real transport failures and redelivery:

- Baseline publication preserves exact key, complete known Debezium 3.6 header shape, timestamp,
  topic, payload bytes, event identity, and explicit partition only where the outbox supplies one.
  A NULL Account partition is never converted into an invented expected partition.
- Connector outage observes the connector PAUSED before committing the fixture, reads the durable
  outbox probe while unavailable, resumes the connector, verifies exact publication, rereads the
  outbox, and asserts the same identity/payload survived.
- Kafka producer unavailability stops Kafka while PostgreSQL remains writable, captures the durable
  Risk and Account probes, restores Kafka/Connect, verifies exact publication, and asserts the same
  durable probes remained unchanged.
- Publication-level duplicate delivery first verifies a new durable event, captures Kafka end
  offsets after that publication, terminates Connect with `SIGKILL` before its next configured
  source-offset flush, and requires the same probe event to appear again after restart. One Kafka
  record therefore cannot satisfy both delivery assertions.

The Kubernetes Risk observer reads lag, age, and the refresh timestamp from one pinned Risk Pod.
It accepts a gauge only when the exported timestamp is at least as new as the corresponding durable
`cdc_delivery_lag.updated_at_unix_ms` row, so a stale zero from another replica cannot satisfy
recovery.

Connector `RUNNING` is only prerequisite/diagnostic evidence. A scenario succeeds only when the
expected durable change passes the Kafka record verification.

When a caller supplies the optional publication-evidence path, the Module retains the verified
topic, event id, partition, offset, expected key/timestamp/header/payload digest, and individual
exact-record checks. A later report validator can therefore reconstruct which Kafka record was
observed instead of trusting an unlinked `exact_kafka_record=true` flag. Raw payload bytes and
unredacted Kafka headers remain outside this retained artifact.

## Downstream duplicate-safety contract

The narrow consumer-side requirement is tested through the existing `QueryProjectionStore`
Interface; it does not pull Issue #137's full replay, Redis, freshness, or deployment scope into
#92. An equivalent Account event delivered at the next Kafka offset advances transport progress
without a second projection, allowing the following unique event to remain contiguous. Reusing the
same event id with different raw payload fails closed as a conflicting event.

## Issue #156 reuse contract

**Historical only.** Issue #202 retired the dedicated Kubernetes Connect
worker-loss Shell runner, REST tunnel, dedicated Java verifier, and fixtures.
The original #156 outcome is not invalidated, but its executable workflow is
no longer an S5 acceptance owner. `OutboxCdcIntegrationTest` now retains
actual RF1/RF3 broker/connector failures, checkpoint-based worker crash, and
redelivery without asserting Kubernetes Pod/task reassignment. The old
requirements below describe the scoped historical #156 evidence.

The former Issue #156 worker-loss case used the now-retired
`scripts/run-local-connect-worker-loss.sh` diagnostic. Its historical acceptance
required the following observations:

1. the current task owner is known,
2. the owning worker actually disappears,
3. the task is reassigned to another worker,
4. a new post-reassignment Account business transition occurs,
5. an outbox baseline is captured before that transition and its durable change is captured through
   the baseline-aware `cdc_read_outbox_probe`, and
6. `cdc_assert_probe_publication` verifies the corresponding Kafka record.

A Ready replacement Pod, a changed REST task listing, or connector `RUNNING` alone cannot satisfy
that data-plane assertion. The diagnostic owns only task-owner selection, Pod deletion, reassignment,
and the Kubernetes/REST adapters; the baseline-aware Module Interface is the sole event-selection
seam. Its controlled Account outbox fixture is deliberately a transport-level transition probe, so
the resulting report does not claim Account RPC/business-transaction semantics or full-local
certification. #176 intentionally contains no Kubernetes worker-loss orchestration.

The former command required a retained, disposable production-like namespace
with a matching run-id and an empty evidence directory. It is no longer available.

The retired command required matching namespace/run-id and scoped source signatures.
It rejected runtime drift before Pod mutation. It recorded verifier drift and checked the
current contract before changing Pods. The former runner checked Flyway and Kafka prerequisites,
Connect workers, ownership, and Pod identity. It deleted one run-owned Pod with a UID
precondition. It then checked task reassignment and published a post-reassignment outbox
event. The removed Shell adapter selected that event and checked its exact Kafka record.
These observations were diagnostic evidence only. They did not establish complete local
resilience or production certification. No current workflow supports this Pod-reassignment test.

The report links every prerequisite snapshot, the pre-delete UID recheck,
`target-delete-observation.json`, `account-transition.json`, and
`account-publication.json`. The delete observation is a schema-versioned,
run-owned record: it contains the target Pod UID, the replacement outcome (or
an explicit `not-found` outcome), the replacement UID when one exists, and the
fact that the original UID is absent. A `replacement-pod` outcome must also
identify that replacement UID in the after-Pod snapshot; a `not-found` outcome
must prove neither the original name nor UID remains. The raw delete log remains
useful for incident diagnosis, but it is not the machine-readable recovery proof.

The publication artifact uses schema version 2. It is emitted only after the
shared verifier succeeds and records the expected contract plus the actual
Kafka observation: partition, offset, broker timestamp, event identity/type,
and SHA-256 digests for the key, payload, complete headers, and canonical
`headers_json`, together with each exact-record check. It deliberately does
not retain raw payload bytes or unredacted headers. A later consumer can
reconstruct the observation chain without trusting an unlinked
`exact_kafka_record=true` flag or a caller-supplied expected value.

The provenance boundary is intentionally layered:

```text
runtime fingerprint  = source-controlled deployment manifests, schemas, connector registration,
                       and phase inputs that shape the retained namespace
image-lock proof      = the retained PASS identity from registry-image-lock, matched to
                       the exact local-images.lock bytes before any observer or Pod mutation
verifier fingerprint = CDC selection/publication rules, fixture contract, and evidence checks,
                       including selected observer/contract paths and content digests
diagnostic evidence   = a new report produced by the current verifier against the retained runtime
```

較好的後續設計不是關閉 provenance，而是把 fingerprint 分成更精準的 scope。只有 runtime
fingerprint 變更才要求重新建立部署；verifier fingerprint 變更會要求重新執行 focused
diagnostic，並在 `provenance.json` 記錄 retained/current verifier fingerprints 與
`verifier_signature_changed`。contract adapter 的 canonical path 與 SHA-256 也會寫入
`provenance.json`；observer 或 contract 內容、path/override 改變時，fingerprint 必然改變，
而且目前 contract 仍會在 observer/Pod mutation 前執行並要求固定 success marker。這保留
fail-closed correctness，也避免把「新的 verifier 通過」誤寫成「舊的 diagnostic report 仍然有效」。

Focused preflight 不只比較檔案 fingerprint：它必須讀取 retained
`phases/registry-image-lock/result.json`，確認 status 是 `PASS`、output identity 是
`sha256:<sha256(local-images.lock)>`，並再次核對 namespace 實際 workload image。這個
上游 evidence binding 讓 generated image lock 不會被呼叫端悄悄替換。Worker-loss report
目前使用 schema version 2；schema 1 envelope 不會被自動升級或接受。

Focused verdict 也使用 schema version 2，並將實際執行的 observer 與 contract script
複製到該次 diagnostic 目錄（`verifier-observer.sh`、`verifier-contract.sh`）。報告保留
canonical path、內容 SHA-256 與相對 evidence file；後續查驗以副本的 digest 為準，不依賴
工作樹中仍存在的外部檔案。副本只作為可攜式 audit evidence，執行時仍使用已解析且先前
fingerprint 過的 canonical script，避免 script 內部以自身目錄載入 shared library 時改變
語義。

## Final Risk publication contract

Issue #126's completed, retained **historical evidence** establishes that the original
post-cutover Risk path published real Admission/outbox -> Debezium ->
`matching.commands` records with an explicit partition and exact payload bytes,
and that the former combined Risk/Kafka Connect restart/equivalent-replay test passed
for its recorded source. S5 has retired that separate operator and its Job.
The current RM-1 Jupiter entry checks a deployed command-topic path, while the
Risk service and CDC tests separately own replay/idempotency and delivery/recovery.
Neither recreates the former combined restart/replay proof on the current source.
The generic Compose Risk fixtures here preserve transport coverage, not the retired
historical test's complete acceptance.

## GitHub Actions coverage

`Java CI` includes Query-service paths, runs repository-wide `staticAnalysis`, and executes the full
Java test suite, including Account/Risk transaction tests and the narrow Query duplicate-safety
regressions.

`CDC CI` checks `git diff --check`, connector ownership/no-direct-producer contracts,
the shared verifier Interface, Matching Kafka contracts, and the live Compose CDC scenarios.

`Flyway CI` remains required because this change adds the Risk migration
`V10__record_cdc_delivery_observations.sql` and the strict artifact-route `V11__require_admission_artifact_route.sql`
under the shared Flyway service convention. The migration contract gate must pass
`./gradlew -q :tools:dependency-tests:test`; the PostgreSQL smoke gate must run
`./gradlew -q verifyPostgres`, and Java CI must pass
`com.simplematch.riskservice.store.RiskServiceFlywayMigrationTest`.

The PR is not ready until the latest head has successful Java CI, CDC CI, and Flyway CI results.

## TDD evidence

The reusable CDC Interface and Query duplicate behavior were introduced as vertical tracer bullets.
On the Red head `f2554bc77c1807f753f586310fe2e7eee8961524`, CDC CI #122 failed because
`cdc_read_outbox_probe` did not yet exist, while Java CI #159 passed static analysis and then failed
`equivalentAccountDuplicateAdvancesTransportProgressWithoutSecondProjection` with
`QueryProjectionGapException`. The subsequent implementation is accepted only after the same public
seams and the complete quality gates turn Green.

## Local verification

Run the focused checks first:

```bash
./gradlew :services:account-service:test \
  --tests '*AccountReservationApplicationServiceTransactionTest'
./gradlew :services:risk-service:test \
  --tests '*OrderAdmissionApplicationServiceTransactionTest'
./gradlew :services:query-service:test \
  --tests '*JdbcQueryProjectionStoreTest'
./gradlew :tools:dependency-tests:test :tools:recovery-evidence-verifier:test
git diff --check HEAD^ HEAD
```

Then run the live transport fault test with Docker and Java 25:

```bash
./gradlew verifyCdc
```

Follow `docs/agents/deployment-test-lessons.md` before starting the containers.
Each profile creates a new run-owned network and containers. Kafka and Connect use dynamically
selected loopback ports that remain bound to their containers across real stop/start faults.
The live task always executes again. Normal build and dependency caches remain available.
A run report records the source fingerprint, actual image config IDs, repository digests, and exact
publication evidence. It writes `PASS` only after verifying removal of every owned container and
network. Reports are in `tools/dependency-tests/build/reports/dependency-tests/cdc/<run-id>/`.
This is dependency transport evidence. It does not establish a complete application path or
Kubernetes recovery certification.

**Historical only (before Issue #202 P5b).** The former 62-phase aggregate could retain a
source-compatible namespace for a focused Risk CDC diagnostic. That diagnostic checked
namespace/run-id ownership, the image lock, deployment inputs, the observer fixture, and
scoped runtime/verifier fingerprints. It checked deployed Risk CDC outbox publication,
lag transitions, Actuator freshness, log redaction, and cleanup.

P5b removed the aggregate and changed the runtime fingerprint. No current command can
produce the compatible retained `run-context`. Issue #202 therefore retired the focused
continuation, observer, and Shell-only tests. Do not execute commands from historical
reports. Historical `FOCUSED_DIAGNOSTIC` verdicts cannot establish current-source
acceptance. For current CDC transport checks, run `./gradlew verifyCdc`. That task does
not validate the retired Kubernetes Risk CDC telemetry scenario.

Finally run the repository Java quality gate:

```bash
./gradlew --no-daemon staticAnalysis
```

The completed #126 `matching.commands` evidence is historical support for #92,
not a reusable PASS for a changed source. For a change to the currently retained
Risk/CDC contracts, run their current source-aligned verification owners.
The original combined Risk/Kafka Connect Pod restart and equivalent-replay gate
cannot be re-run through the retired operator; its lost scope is recorded in S5.

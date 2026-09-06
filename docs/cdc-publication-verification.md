# Risk and Account CDC verification

This document maps Issue #92 to retained automated evidence and records the reusable observation
Interface intended for Issue #156. The two issues keep different fault orchestration: #92 owns local
connector/Kafka interruption, while #156 owns distributed Kafka Connect worker loss and task
reassignment. They share how the CDC data path is observed and verified.

## Evidence layers

Account application integration and CDC publication integration remain separate seams. The current
Compose profile does not start Account application code, so its direct SQL outbox fixtures certify
Debezium EventRouter/Kafka mapping and recovery; they are not described as a single-path Account
application-to-Kafka E2E.

- `AccountReservationApplicationServiceTransactionTest` proves Account authority mutation and the
  `account.lifecycle` outbox row commit or roll back together. It independently fixes the existing
  Account topic, message-key, and lifecycle-event contract.
- `OrderAdmissionApplicationServiceTransactionTest` proves terminal Risk admission state and its
  outbox row share one transaction.
- `scripts/verify-outbox-connector-contracts.sh` verifies each retained connector reads only its
  owning outbox table, publishes `payload_type` as the Debezium `eventType` header together with
  `headers_json`, and rejects current direct Kafka producer types in Risk and Account production
  Java sources.
- `scripts/test-cdc-observer-fixture-contract.sh` verifies that the Kubernetes CDC observer fixture
  writes the same `event_id`, `content_type`, and `payload_type` header shape as a normal outbox
  event. It runs as part of the Phase 1 preflight, before migrations or workload startup.
- `scripts/run-outbox-cdc-contract-check.sh` owns Compose lifecycle and fault injection only. It
  provides environment-specific Adapters to the shared verification Module.

## Shared CDC verification Module

`scripts/lib/cdc-verifier.sh` exposes one Interface for #92 and future #156 callers:

1. `cdc_capture_topic_end_offsets <topic> <snapshot-file>`
2. `cdc_wait_for_connector_state <connector-name> <expected-state> [timeout-seconds]`
3. `cdc_capture_outbox_baseline <schema> <aggregate-type> <aggregate-id> <baseline-file>`
4. `cdc_read_outbox_probe <schema> <aggregate-type> <aggregate-id> <probe-file> [baseline-file]`
5. `cdc_assert_same_probe <expected-probe> <observed-probe>`
6. `cdc_assert_probe_publication <probe-file> <baseline-snapshot> [publication-evidence-file]`
7. `cdc_validate_publication_evidence <publication-evidence-file>`

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
  `scripts/lib/cdc-verifier.sh` owns outbox selection and exact publication checks, while
  `scripts/lib/connect-worker-loss.sh` owns task-owner identity, Pod-loss, reassignment, and report
  linkage. A caller should not repeat those rules.
- **Seam** (in the Michael Feathers sense) is the location where the Interface can be substituted
  without editing the Module. In this design, `CDC_OUTBOX_EXEC`, `CDC_KAFKA_EXEC`, and
  `CDC_CONNECT_STATUS_EXEC` are seams: the Module invokes them, while a test fake, Compose command,
  or Kubernetes command can fill the same slot. A seam is therefore a replaceable code location,
  not necessarily a service, network, or DDD boundary.
- **Adapter** is the concrete implementation plugged into a seam. For example, the Kubernetes
  runner's `postgres_exec`, `kafka_exec`, and `connect_status` translate `kubectl`, Kafka CLI, and
  REST calls into the Module's narrow Interface. They provide access; they do not decide whether
  the evidence passes.
- **Runner** is the scenario orchestrator. It orders preflight, fault injection, cleanup, and
  evidence files. Shell is appropriate here because it is excellent CI/deployment glue and a
  transparent wrapper around existing command-line tools. It is a poor home for domain semantics,
  complicated asynchronous state machines, or a second copy of the evidence rules.

The industry lifecycle is consequently additive: keep the Contract, schema, and Module while they
are supported; keep the runner while it is an active operator or CI entry point; archive a runner
only after a replacement has the same Interface, evidence shape, and regression coverage. Historical
fixtures and evidence may be archived immediately when they are no longer current authorities, but
they remain useful incident records. Archiving the shell wrapper must never remove the executable
specification that prevents a false `PASS`.

Three dependency seams are injected through environment-specific Adapters:

- `CDC_OUTBOX_EXEC` executes the Module-owned PostgreSQL query.
- `CDC_CONNECT_STATUS_EXEC` returns one connector status document.
- `CDC_KAFKA_EXEC` executes Kafka CLI reads.

The live Compose harness, `scripts/test-cdc-verifier.sh` fakes, and the #156 focused Kubernetes
runner are concrete Adapters at each seam. They supply environment-specific execution without
copying the Module Implementation. Docker Compose commands, Pod/node manipulation, worker
selection, task-owner reassignment, and namespace lifecycle do not belong in this Module.

## CdcProbeIdentity

`cdc_read_outbox_probe` materializes a temporary test-side JSON observation document called a
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

The live transport check uses the same `cdc_assert_probe_publication` Interface in every scenario:

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

Issue #156 remains responsible for Kubernetes/Connect distributed-runtime orchestration. Its
worker-loss case is exposed as the focused `scripts/run-local-connect-worker-loss.sh` diagnostic and
must independently prove:

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

Run it only against a retained, disposable production-like namespace whose run-id is supplied
exactly as labelled; the evidence directory must be empty and the command never applies manifests or
deletes the cluster:

```bash
bash scripts/run-local-connect-worker-loss.sh \
  --namespace <run-namespace> \
  --namespace-run-id <namespace-run-id> \
  --retained-evidence-dir out/certification/local-production-like \
  --evidence-dir out/resilience/connect-worker-loss-<run-id>
```

The retained directory must contain the same namespace/run-id and the `cdc_runtime_signature` and
`cdc_verifier_signature` recorded by the full production-like run. The focused command compares the
runtime signature before any Pod mutation; a runtime mismatch fails closed and requires a fresh
source-aligned full run. A verifier-signature mismatch is recorded as drift, then the current verifier
contract is executed before any Pod mutation and the diagnostic writes new evidence. No old diagnostic
report is reused. The diagnostic first verifies Flyway/topic prerequisites, two PVC-free Connect workers, RF3/minISR2
internal topics, PDB protection, service-owned connector table/header boundaries, and strict Pod
identity. It applies a JSON-Patch UID precondition and a run-unique marker, then deletes only the
uniquely marked Pod and proves that the original UID disappears before waiting for reassignment. It then requires the same
task id to move to a different worker and Pod UID, captures the outbox baseline before inserting one
run-owned lifecycle fixture, and delegates post-transition selection and exact Kafka verification to
`cdc-verifier.sh`. A passed report is focused diagnostic evidence only; it must be consumed by the
parent #151 runner rather than relabelled as a complete local certification.

The report links every prerequisite snapshot, the pre-delete UID recheck,
`target-delete-observation.json`, `account-transition.json`, and
`account-publication.json`. The delete observation is a schema-versioned,
run-owned record: it contains the target Pod UID, the replacement outcome (or
an explicit `not-found` outcome), the replacement UID when one exists, and the
fact that the original UID is absent. The raw delete log remains useful for
incident diagnosis, but it is not the machine-readable recovery proof.

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

Issue #126 remains the canonical deployed certification for the post-cutover Risk path. Its retained
evidence proves real Risk Admission/outbox -> Debezium -> `matching.commands` publication with the
persisted explicit partition and exact Kafka payload bytes, plus restart/equivalent-replay behavior.
The generic Compose Risk fixtures here preserve transport coverage but do not replace #126.

## GitHub Actions coverage

`Java CI` includes Query-service paths, runs repository-wide `staticAnalysis`, and executes the full
Java test suite, including Account/Risk transaction tests and the narrow Query duplicate-safety
regressions.

`CDC CI` checks `git diff --check`, Markdown links, connector ownership/no-direct-producer contracts,
the shared verifier Interface, Matching Kafka contracts, and the live Compose CDC scenarios.

`Flyway CI` remains required because this change adds the Risk migration
`V10__record_cdc_delivery_observations.sql` and the strict artifact-route `V11__require_admission_artifact_route.sql`
under the shared Flyway service convention. The migration contract gate must pass
`bash scripts/test-flyway-services.sh`; the PostgreSQL smoke gate must run
`bash scripts/run-flyway-ci-checks.sh`, and Java CI must pass
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
bash scripts/verify-outbox-connector-contracts.sh
bash scripts/test-cdc-verifier.sh
bash scripts/test-check-markdown-links.sh
git diff --check HEAD^ HEAD
```

Then run the live transport fault test on a machine with Docker Compose:

```bash
bash scripts/run-outbox-cdc-contract-check.sh
```

Before that Docker run, follow `docs/agents/deployment-test-lessons.md` preflight requirements and
confirm the configured PostgreSQL/Kafka/Connect ports are free. Each invocation generates a unique,
run-owned Compose project. An explicitly supplied `SIMPLEMATCH_CDC_COMPOSE_PROJECT` must own no
existing container, network, or volume. Cleanup removes only resources with that exact project label,
removes its disposable volumes, and fails the run if any run-owned resource remains.

When a retained full production-like run has already passed every predecessor of the Risk CDC
observer but that observer ended with a transient failure, use the focused diagnostic instead of
starting another full deployment:

```bash
bash scripts/run-local-cdc-delivery-focused-diagnostic.sh \
  --evidence-dir out/certification/<retained-run> \
  --timeout-seconds 180
```

The command reads the retained `run-context`, verifies the full proof profile, disposable
namespace/run-id, executed dependency results, immutable image lock, deployed workload images,
session ConfigMaps, and PostgreSQL secrets, and only then invokes the existing observer. The
run-context records two scoped identities for this continuation: `cdc_runtime_signature` covers
the manifests, Risk CDC runtime, image/fingerprint, and orchestration inputs that created the
retained namespace; `cdc_verifier_signature` covers the observer, fixture, and focused verifier
code. An unrelated source commit does not change the runtime signature and therefore does not
force a new deployment. If only the verifier signature changes, the fast
`test-cdc-observer-fixture-contract.sh` check runs before the observer. A runtime-signature drift,
namespace/input/image/dependency drift, or a retained context from before these scoped identities
were recorded fails closed and requires a fresh full run.

The command writes a `FOCUSED_DIAGNOSTIC` verdict below the retained run's
`focused-diagnostics/cdc-delivery/` directory. That verdict is deliberately not a phase result and
cannot close or upgrade the full certification; even a PASS proves only the observer against the
retained runtime and current verifier, not a new full source revision.

Finally run the repository Java quality gate:

```bash
./gradlew --no-daemon staticAnalysis
```

The only non-PR-CI dependency for #92 is completed #126's deployed `matching.commands`
certification. Re-run #126 locally only if that deployment path or contract changes.

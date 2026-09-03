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
  owning outbox table and rejects current direct Kafka producer types in Risk and Account production
  Java sources.
- `scripts/run-outbox-cdc-contract-check.sh` owns Compose lifecycle and fault injection only. It
  provides environment-specific Adapters to the shared verification Module.

## Shared CDC verification Module

`scripts/lib/cdc-verifier.sh` exposes one Interface for #92 and future #156 callers:

1. `cdc_capture_topic_end_offsets <topic> <snapshot-file>`
2. `cdc_wait_for_connector_state <connector-name> <expected-state> [timeout-seconds]`
3. `cdc_read_outbox_probe <schema> <aggregate-type> <aggregate-id> <probe-file>`
4. `cdc_assert_same_probe <expected-probe> <observed-probe>`
5. `cdc_assert_probe_publication <probe-file> <baseline-snapshot>`

The Module hides PostgreSQL outbox-row parsing, event-id discovery, payload hashing, bounded Connect
status polling, Kafka offset-window selection, Debezium event lookup, exact key/header/timestamp/
partition/value checks, topology validation, and Kafka diagnostics. Removing the Module would spread
that knowledge back across Compose and Kubernetes scenario callers.

Three dependency seams are injected through environment-specific Adapters:

- `CDC_OUTBOX_EXEC` executes the Module-owned PostgreSQL query.
- `CDC_CONNECT_STATUS_EXEC` returns one connector status document.
- `CDC_KAFKA_EXEC` executes Kafka CLI reads.

The live Compose harness and `scripts/test-cdc-verifier.sh` fake are two concrete Adapters at each
seam. Future #156 Kubernetes orchestration can supply Kubernetes-backed Adapters without copying the
Module Implementation. Docker Compose commands, Pod/node manipulation, worker selection, task-owner
reassignment, and namespace lifecycle do not belong in this Module.

## CdcProbeIdentity

`cdc_read_outbox_probe` materializes a temporary test-side JSON observation document called a
`CdcProbeIdentity`. It is infrastructure test state, not a domain object and not a production
contract. Callers identify a new business change by the existing durable outbox aggregate identity;
for Account reservation publication this is `account_reservation + reservation_id`. The Module then
discovers the internally generated outbox `event_id` and records:

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
  Risk, Account, and Marketdata probes, restores Kafka/Connect, verifies exact publication, and
  asserts the same durable probes remained unchanged.
- Publication-level duplicate delivery first verifies a new durable event, captures Kafka end
  offsets after that publication, terminates Connect with `SIGKILL` before its next configured
  source-offset flush, and requires the same probe event to appear again after restart. One Kafka
  record therefore cannot satisfy both delivery assertions.

Connector `RUNNING` is only prerequisite/diagnostic evidence. A scenario succeeds only when the
expected durable change passes the Kafka record verification.

## Downstream duplicate-safety contract

The narrow consumer-side requirement is tested through the existing `QueryProjectionStore`
Interface; it does not pull Issue #137's full replay, Redis, freshness, or deployment scope into
#92. An equivalent Account event delivered at the next Kafka offset advances transport progress
without a second projection, allowing the following unique event to remain contiguous. Reusing the
same event id with different raw payload fails closed as a conflicting event.

## Issue #156 reuse contract

Issue #156 remains responsible for Kubernetes/Connect distributed-runtime orchestration. Its future
worker-loss case belongs in existing `scripts/run-local-resilience.sh` and must independently prove:

1. the current task owner is known,
2. the owning worker actually disappears,
3. the task is reassigned to another worker,
4. a new post-reassignment Account business transition occurs,
5. its durable outbox change is captured through `cdc_read_outbox_probe`, and
6. `cdc_assert_probe_publication` verifies the corresponding Kafka record.

A Ready replacement Pod, a changed REST task listing, or connector `RUNNING` alone cannot satisfy
that data-plane assertion. #176 intentionally contains no Kubernetes worker-loss orchestration.

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
`V10__record_cdc_delivery_observations.sql` under the shared Flyway service convention. The
migration contract gate must pass `bash scripts/test-flyway-services.sh`; the PostgreSQL smoke
gate must run `bash scripts/run-flyway-ci-checks.sh`, and Java CI must pass
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

Finally run the repository Java quality gate:

```bash
./gradlew --no-daemon staticAnalysis
```

The only non-PR-CI dependency for #92 is completed #126's deployed `matching.commands`
certification. Re-run #126 locally only if that deployment path or contract changes.

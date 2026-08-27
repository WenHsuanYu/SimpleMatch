# Critical consumer end-to-end certification

This directory contains deployed-system certification for the three critical
`matching.events` consumers: Persistence, Account, and QuickFIX Gateway.

The scripts exercise real Kubernetes workloads, Kafka, PostgreSQL, Kafka
Connect, and an external FIX session. They are not unit-test helpers and do not
call consumer implementation methods directly.

## Structure

- `run-failure-certification.sh` owns only the failure and recovery scenario.
- `lib/matching-status.sh` validates Matching runtime evidence and normalizes
  Kafka committed positions. It performs no Kubernetes or Kafka I/O.
- `lib/system-observation.sh` collects one bounded Gateway readiness
  observation. It parallelizes Matching reads and rejects an observation when
  Kafka positions change during collection.
- `lib/cluster-data.sh` contains Kubernetes, Kafka, PostgreSQL, and test-fixture
  access.
- `lib/test-interfaces.sh` contains external FIX, Kafka Connect, and Gateway
  operations adapters used only by the certification.
- `lib/failure-recovery.sh` contains failure injection, recovery verification,
  diagnostics, and environment restoration.
- `tests/` contains shell contracts for the modules and deployment ordering.

The historical entrypoint
`scripts/run-critical-consumer-failure-certification.sh` remains as a thin
compatibility wrapper. New documentation and CI use the path in this directory.

## Observation rules

A Gateway readiness observation is accepted only when its evidence has clear
sources:

- Matching `READY`/`OPEN` and `observedAt` come from
  `runtime-metrics.json`, including its `updated_at_epoch_ms` source timestamp.
- Matching durable progress comes from Kafka consumer-group `CURRENT-OFFSET`.
- Kafka log-end positions come from `kafka-get-offsets.sh`.
- Critical-consumer progress comes from the durable PostgreSQL progress tables;
  `last_processed_offset + 1` converts the last processed record offset to the
  next Kafka position.
- Risk and critical-consumer process availability comes from current Kubernetes
  workload status.

The collector does not claim a cross-system atomic snapshot. Instead it reads
Kafka positions before and after the other observations. If either topic moves,
the attempt is discarded and retried. Matching Pods are sampled in parallel,
and their Pod UID is checked before and after reading runtime metrics so a Pod
replacement cannot combine evidence from two processes.

## FIX submission boundary

The prepared FIX client logs on before the freshness-sensitive admission window
and waits for a release file. The runner then supplies three fresh observations,
opens Gateway admission, and immediately releases the client. The normal Gateway
stale-observation monitor remains enabled throughout the run.

The Risk outbox connector is intentionally paused before the order is released.
This is a test barrier, not a health claim: it keeps the accepted Risk command
durable without allowing `matching.commands` to advance before Matching is
stopped.

## Failure evidence

Both PASS and FAIL runs write `verdict.json` after the evidence directory has
been initialized. A failure verdict records the stage and reason; cleanup also
records whether environment restoration failed.

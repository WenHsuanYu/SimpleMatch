# RM-1 Risk restart and equivalent-replay certification

This runbook completes the restart/replay portion of RM-1 / issue #126 after a passing deployed
Risk-to-`matching.commands` baseline exists. It reuses that baseline's durable command identity; it
does not create a second business order, reset Debezium offsets, or rewrite Risk persistence.

## What is being proved

The baseline verifier already proves:

```text
Risk gRPC
-> durable Admission
-> Account reservation
-> Risk outbox
-> Debezium / Kafka Connect
-> matching.commands
-> exact key / partition / payload bytes
-> typed MatchingCommand semantics
```

The restart/replay gate adds this boundary:

```text
passing baseline command
-> snapshot durable Admission / outbox / matching.commands offsets
-> replace every Risk Service Pod
-> replace every Kafka Connect Pod
-> connector returns RUNNING
-> matching.commands offsets remain unchanged
-> resubmit the same durably-equivalent command identity
-> Risk returns the existing terminal ACCEPTED synchronously
-> Admission identity / artifact / partition remain unchanged
-> exactly one matching.commands outbox row remains
-> exact outbox MatchingCommand bytes remain unchanged
-> matching.commands offsets remain unchanged after replay
```

Risk's durable equivalence intentionally excludes transport metadata such as event creation time. The
validated `AdmissionCommand` contains command/order/account identity, order facts, FIX business
identity, and routing reference. The replay verifier therefore regenerates the request using the
same `runId`, `accountId`, trading day, and immutable Market Reference artifact, and compares the
serialized evidence for all durable request facts to the passing baseline.

The controlled restart is deliberately stricter than the system's general at-least-once allowance:
a graceful Risk/Kafka Connect rollout must not append another physical `matching.commands` record.
Crash-window duplicate tolerance remains covered by lower-level CDC/consumer tests; this scenario is
specifically the terminal equivalent-replay contract.

## Preconditions

Use the same retained local production-like namespace that produced a passing initial RM-1 run. The
baseline evidence directory must contain at least:

```text
run-metadata.json
request.json
selected-instrument.json
admission-outcome.json
matching-command-record.json
matching-command-decoded.json
risk-admission.json
risk-outbox.json
verifier-verdict.json
verdict.json
```

Both verdicts must be `PASS`, and the Admission outcome must be terminal `ACCEPTED`.

Before running the deployed replay, build and load the verifier image from the branch containing the
replay mode:

```bash
scripts/build-local-images.sh --service risk-matching-e2e-verifier

kind load docker-image \
  --name simplematch-live \
  simplematch/risk-matching-e2e-verifier:local
```

Run the static deployment contracts and focused verifier/Risk tests first:

```bash
bash scripts/test-risk-matching-e2e-manifest.sh
bash scripts/test-risk-matching-restart-replay-manifest.sh

./gradlew --no-daemon \
  :tools:risk-matching-e2e-verifier:test \
  :services:risk-service:test
```

## Run

For the retained namespace used by the passing baseline:

```bash
scripts/run-risk-matching-restart-replay-e2e.sh \
  --namespace simplematch-rm1-20260817-v3 \
  --baseline-evidence-dir out/certification/rm1-20260817-v2/rm1-01 \
  --evidence-dir out/certification/rm1-20260817-v3/restart-replay
```

The baseline path does not have to share the namespace name in its filesystem path. The harness uses
`run-metadata.json` as authority and fails if its recorded namespace differs from `--namespace`.
The new evidence directory must be empty.

## Fail-closed boundaries

The harness fails if any of the following occurs:

- the retained Admission or outbox no longer equals the passing baseline evidence;
- the baseline command has zero or more than one Risk `matching.commands` outbox row;
- either Risk or Kafka Connect does not replace every Pod UID recorded before restart;
- `risk-service-outbox` does not return to `RUNNING` after Kafka Connect replacement;
- any `matching.commands` partition end offset advances during the controlled restart;
- replay reconstructs different durable request facts or a different command identity;
- Risk replay enters a new pending/recovery path instead of returning terminal `ACCEPTED`
  synchronously;
- the persisted Admission artifact identity or explicit partition changes;
- the outbox key, partition, payload type, aggregate identity, or exact payload bytes change;
- replay creates a second matching-command outbox row;
- any `matching.commands` end offset advances after terminal replay.

The harness never uses `DELETE`/`UPDATE` against `risk_service.admission_journal` or
`risk_service.outbox`, and it never edits Kafka Connect offsets. Failure diagnostics are read-only.

## Evidence

A passing replay directory contains evidence including:

```text
risk-admission-before-restart.json
risk-outbox-before-restart.json
matching-offsets-before-restart.json
risk-pod-uids-before.json
risk-pod-uids-after.json
kafka-connect-pod-uids-before.json
kafka-connect-pod-uids-after.json
connector-status-before-restart.json
connector-status-after-restart.json
matching-offsets-after-restart.json
verifier-run-config.json
verifier/request.json
verifier/admission-submit.json
verifier/admission-outcome.json
verifier/verifier-verdict.json
risk-admission-after-replay.json
risk-outbox-after-replay.json
matching-offsets-after-replay.json
verdict.json
```

The final `verdict.json` must report `RM1_RESTART_EQUIVALENT_REPLAY` with `PASS`.

## Completion rule for #126

Do not mark #126 complete from source changes or CI alone. Completion requires all of the following on
the revision being claimed:

1. the original deployed RM-1 Risk -> `matching.commands` verifier passes in a fresh/retained local
   production-like namespace;
2. this restart/equivalent-replay scenario passes against that same retained command and namespace;
3. Java static analysis and the complete relevant service/verifier tests pass;
4. the existing artifact readiness, explicit-partition, Open/Close Barrier, no-runtime-Market-
   Reference-fallback, CDC-backpressure-default, Flyway/PostgreSQL, and CDC contract gates remain
   green.

Only after those executable gates are green should the RM-1 inventory entry be changed from
`PARTIAL` to `COMPLETED` and issue #126's acceptance criteria be checked off.

# RM-1 Risk-to-Matching deployed E2E verification

This runbook covers the repository-owned local production-like evidence for RM-1 / issue #126. It
proves one real accepted Risk command travels through the durable Risk admission/outbox boundary,
the retained Debezium connector, and the production-shaped `matching.commands` topic. It does not
claim external staging or production certification.

## Why this verifier exists

Unit and integration tests already prove the individual Risk routing, transaction, outbox, and
protobuf contracts. The remaining RM-1 gap is a deployed correlation across those contracts. A
successful check must therefore identify one command end to end rather than merely observe that
Risk is Ready, the connector is RUNNING, or the Kafka topic contains some record.

The retained verifier separates responsibilities deliberately:

- `scripts/run-risk-matching-command-e2e.sh` owns Kubernetes orchestration, the run-scoped Account
  cash-limit fixture, PostgreSQL evidence, connector status, and cleanup.
- `tools:risk-matching-e2e-verifier` owns typed Market Reference validation, deterministic scenario
  construction, Risk gRPC submission, Kafka observation, protobuf decoding, and semantic assertions.
- production Risk and Matching code are not bypassed or replaced.

## Preconditions

Create and retain the local environment first:

```bash
scripts/manage-simplematch-live.sh create

SIMPLEMATCH_CERTIFICATION_TRADING_DAY=2026-08-17 \
SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST="$(
  realpath tools/market-reference-builder/config/market-reference/approved/2026-08-17/delivery/manifest.yaml
)" \
SIMPLEMATCH_CERTIFICATION_NAMESPACE=simplematch-rm1-20260817 \
SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR="$PWD/out/certification/rm1-20260817" \
scripts/run-local-production-like-certification.sh --keep-resources
```

The verifier expects the retained namespace to contain Ready Risk, Account, PostgreSQL, Matching,
Kafka, and Kafka Connect workloads, the `matching-daily-artifact` ConfigMap, and a RUNNING
`risk-service-outbox` connector/task.

The `simplematch/flyway-runner:local` image must be rebuilt from the worktree containing
`tools/risk-matching-e2e-verifier`; the normal local certification image build does this
automatically. The verifier reuses that image only as a JDK/repository execution environment. It
does not invoke Flyway.

## Run

From the repository root:

```bash
scripts/run-risk-matching-command-e2e.sh \
  --namespace simplematch-rm1-20260817 \
  --trading-day 2026-08-17 \
  --evidence-dir out/certification/rm1-20260817/rm1
```

The script is fail-closed and bounded. It performs these checks:

1. waits for the existing runtime prerequisites;
2. verifies the retained Risk Debezium connector and tasks are `RUNNING`;
3. creates a run-scoped Account UUID and provisions only an `ACCOUNT/*` daily cash limit;
4. starts one ephemeral verifier Pod carrying `app.kubernetes.io/part-of=simplematch`;
5. validates the exact mounted artifact bytes and external checksum with the shared startup
   validator;
6. deterministically selects the first eligible `(venueMic, symbol)` instrument with final price
   facts and reads its explicit artifact partition;
7. snapshots all 15 `matching.commands` end offsets before submission;
8. submits a real BUY/LIMIT/ROD board-lot order through
   `simplematch.risk.v2.OrderAdmissionService/SubmitNewOrder`;
9. requires Risk to accept the order and return the artifact-assigned partition;
10. consumes only records after the pre-submit offset boundary, decodes `MatchingCommand`, and
    rejects wrong-partition or conflicting same-key payloads;
11. reads the owner-owned `risk_service.admission_journal` and `risk_service.outbox` rows;
12. requires the Admission artifact identity/partition, outbox key/partition/payload type, and exact
    outbox payload bytes to agree with the Kafka record.

At-least-once Kafka delivery is handled intentionally: multiple physical records with the same
`commandId` are accepted only when they are on the same partition and have byte-identical payloads.
A same-key record on another partition or with different bytes fails the gate.

## Evidence

The requested evidence directory contains, at minimum:

```text
run-metadata.json
market-reference-configmap.json
connector-status.json
account-fixture.log
selected-instrument.json
request.json
response.json
matching-offsets-before.json
matching-command-record.json
matching-command-decoded.json
risk-admission.json
risk-outbox.json
verifier-verdict.json
verdict.json
verifier.log
```

`verdict.json` is the outer cross-layer verdict. `verifier-verdict.json` is the typed Java
gRPC/Kafka verdict. A successful run requires both to report `PASS`.

On failure the harness also captures bounded Pod/workload inventory plus recent Risk, Account, and
helper logs. Diagnostic collection is best-effort and must not overwrite the original failure.

## Current boundary

This first retained verifier closes the missing deployed **accepted New Order** path and checks
initial at-least-once duplicate consistency. It intentionally does not yet claim the restart/replay
portion of the complete RM-1 acceptance criteria. Before RM-1 is changed from `PARTIAL` to
`COMPLETED`, extend the retained scenario with an explicit Risk/connector restart or pause/resume
boundary and prove that the same durable command identity preserves artifact identity, partition,
and command semantics after replay.

Do not mark RM-1 complete merely because this script exists. Status changes require a fresh
canonical local production-like run and retained evidence from the version of the verifier being
claimed.

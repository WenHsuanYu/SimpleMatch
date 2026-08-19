# RM-1 Risk-to-Matching deployed E2E verification

This runbook covers the repository-owned local production-like evidence for RM-1 / issue #126. It
proves one real accepted Risk command travels through the durable Risk admission/outbox boundary,
the retained Debezium connector, and the production-shaped `matching.commands` topic. It does not
claim external staging or production certification.

## Design

The verifier is split into stable layers so each concern has one owner:

- `tools/risk-matching-e2e-verifier` owns typed Market Reference validation, deterministic scenario
  construction, Risk gRPC submission/reconciliation, Kafka observation, protobuf decoding, and
  semantic assertions.
- `RiskAdmissionProbe` treats the initial submission response and durable reconciliation as one
  bounded state machine. It can observe `GetAdmissionOutcome`, but it never resubmits the order and
  never owns saga recovery.
- `deploy/docker/Dockerfile.risk-matching-e2e-verifier` builds that tool into a dedicated runtime
  image. Gradle is used while building the image, not inside the deployed verification Pod.
- `deploy/k8s/verification/risk-matching-e2e-verifier-job.yaml` owns Kubernetes execution policy:
  Job retry/deadline semantics, security contexts, resource limits, volume mounts, and CLI wiring.
- `scripts/run-risk-matching-command-e2e.sh` owns orchestration only: prerequisite checks, the
  run-scoped Account fixture, immutable run configuration, evidence collection, PostgreSQL
  cross-checks, and cleanup.

The shell harness must not embed Kubernetes YAML, reimplement protobuf parsing, recalculate routing,
or drive Risk recovery. Production Risk and Matching code are not bypassed.

The verifier has one end-to-end timeout budget. Submission, reconciliation, and Kafka observation
all consume the same remaining budget; a later stage cannot receive a fresh copy of the original
timeout.

## Admission completion semantics

Two admission paths are valid and must satisfy the same terminal assertions.

`SYNCHRONOUS_ACCEPTED` means `SubmitNewOrder` returned an accepted response within the initial RPC.
The response identity and artifact-assigned partition are validated before Kafka observation.

`RECOVERED_ACCEPTED` covers an ambiguous remote outcome. If `SubmitNewOrder` returns gRPC
`UNAVAILABLE`, the verifier queries the existing public `GetAdmissionOutcome(command_id)` API. A
durable `PENDING` result is observed until Risk's own scheduled recovery reaches a terminal state.
The verifier does not call `SubmitNewOrder` again. `ACCEPTED` continues to Kafka verification;
`REJECTED`, `NOT_FOUND`, an identity mismatch, a non-recoverable gRPC error, or expiration of the
shared deadline fails the gate.

This distinction is required because an Account reservation can commit before the caller receives
the response. `UNAVAILABLE` therefore describes uncertainty at the synchronous boundary; it is not
itself proof that the durable admission failed.

## Static verification

Before running the deployed check:

```bash
bash scripts/test-risk-matching-e2e-manifest.sh
bash scripts/test-matching-topic-cutover.sh

./gradlew --no-daemon \
  :tools:risk-matching-e2e-verifier:test
```

The manifest test protects the verifier's Job type, no-retry behavior, security context, image,
resource budget, Market Reference mount, run-ConfigMap argument contract, normalized Admission
evidence contract, and fail-closed evidence-directory behavior. The topic cutover test protects the
production-shaped `matching.commands` / `matching.events` contract and prevents the legacy
`matching.executions` consumer/topic from being re-enabled by deployment or local certification
configuration.

## Build the dedicated verifier image

For an already-retained certification namespace, build and load only the verifier image:

```bash
scripts/build-local-images.sh \
  --service risk-matching-e2e-verifier

kind load docker-image \
  --name simplematch-live \
  simplematch/risk-matching-e2e-verifier:local
```

A fresh normal `scripts/run-local-production-like-certification.sh` build/load also includes this
image because it is part of the repository local-image inventory.

The running verifier does not copy the repository, start Gradle, or compile build logic. The image
contains the Gradle `installDist` application produced during image build.

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

The retained namespace must contain Ready Risk, Account, PostgreSQL, Matching, Kafka, and Kafka
Connect workloads, the `matching-daily-artifact` ConfigMap, and a RUNNING
`risk-service-outbox` connector/task.

## Run

From the repository root, use a new or empty evidence directory:

```bash
scripts/run-risk-matching-command-e2e.sh \
  --namespace simplematch-rm1-20260817 \
  --trading-day 2026-08-17 \
  --evidence-dir out/certification/rm1-20260817/rm1-02
```

A non-empty evidence directory is rejected. Certification artifacts from separate runs must never be
mixed or silently overwritten.

The script intentionally allows only one verifier run at a time in one disposable namespace. It
uses the stable helper identities:

```text
Job/risk-matching-e2e-verifier
ConfigMap/risk-matching-e2e-run
```

The ConfigMap is immutable and contains only run data: trading day, run-scoped account UUID, run ID,
and timeout. A second invocation refuses to replace retained helper resources because doing so could
destroy failure evidence or correlate two commands to one run.

`--keep-helper` preserves the Job and ConfigMap after the run. Remove them explicitly before a later
run:

```bash
kubectl -n simplematch-rm1-20260817 delete \
  job/risk-matching-e2e-verifier \
  configmap/risk-matching-e2e-run
```

## What the gate proves

The harness performs these checks:

1. verifies the canonical `kind-simplematch-live` context and retained namespace;
2. waits for Risk, Account, Kafka Connect, PostgreSQL, and the Matching fleet;
3. verifies the retained `risk-service-outbox` connector and its task are `RUNNING`;
4. creates a run-scoped Account UUID and provisions only an `ACCOUNT/*` daily cash limit;
5. creates the immutable `risk-matching-e2e-run` ConfigMap;
6. creates the repository-owned verifier Job without rendering YAML in shell;
7. validates the exact mounted Market Reference bytes and external checksum with the shared startup
   validator;
8. deterministically selects the first eligible `(venueMic, symbol)` instrument with final price
   facts and reads its explicit artifact partition;
9. snapshots all 15 `matching.commands` end offsets before submission;
10. submits a real BUY/LIMIT/ROD board-lot order through
    `simplematch.risk.v2.OrderAdmissionService/SubmitNewOrder`;
11. accepts either a validated synchronous Risk acceptance or an `UNAVAILABLE` submission that
    subsequently reaches durable `ACCEPTED` through Risk-owned recovery and `GetAdmissionOutcome`;
12. consumes only records after the pre-submit offset boundary, decodes `MatchingCommand`, and
    rejects wrong-partition or conflicting same-key payloads;
13. copies verifier-owned JSON evidence while the image's bounded evidence hand-off wrapper keeps
    the container alive;
14. acknowledges evidence collection so the Job exits with the original Java verifier status;
15. reads the owner-owned `risk_service.admission_journal` and `risk_service.outbox` rows;
16. requires the Admission artifact identity/partition, outbox key/partition/payload type, and exact
    outbox payload bytes to agree with the Kafka record.

At-least-once Kafka delivery is handled intentionally: multiple physical records with the same
`commandId` are accepted only when they are on the same partition and have byte-identical payloads.
A same-key record on another partition or with different bytes fails the gate.

## CDC backpressure boundary

The local RM-1 overlay explicitly sets
`SIMPLEMATCH_RISK_SERVICE_ADMISSION_CDC_BACKPRESSURE_ENABLED=false`. This is a scoped certification
boundary, not a healthy-CDC simulation and not a production default. RM-1 verifies the real durable
Risk outbox -> Debezium -> `matching.commands` path directly, including connector state and exact
Kafka bytes, so manually refreshing `risk_service.cdc_delivery_lag` would add synthetic health data
without strengthening the RM-1 claim.

The production/default Risk configuration keeps CDC backpressure enabled. Issue #138 owns the
runtime producer that must continuously derive a fresh delivery-lag measurement from real
outbox/CDC/Kafka progress. Until that producer exists and has its own outage/recovery evidence, this
RM-1 verifier does not claim that the CDC lag safety controller is operationally certified.

## Evidence hand-off

The Java verifier writes structured JSON under `/tmp/evidence`. The dedicated image entrypoint runs a
small repository-owned hand-off wrapper:

```text
Java verifier exits
→ wrapper writes .exit-code and .ready
→ host harness copies /tmp/evidence
→ host writes .collected
→ wrapper exits with the original verifier status
→ Kubernetes Job becomes Complete or Failed
```

This exists because `kubectl cp` requires a running container. The hand-off is bounded and the Job
also has `activeDeadlineSeconds`, so a disconnected harness cannot leave the verifier alive
indefinitely.

## Evidence

The requested evidence directory contains, at minimum:

```text
run-metadata.json
market-reference-configmap.json
connector-status.json
account-fixture.log
verifier-run-config.json
selected-instrument.json
request.json
admission-submit.json
admission-outcome.json
matching-offsets-before.json
matching-command-record.json
matching-command-decoded.json
risk-admission.json
risk-outbox.json
verifier-verdict.json
verdict.json
verifier.log
```

`admission-submit.json` records the initial gRPC code and any synchronous response facts.
`admission-outcome.json` normalizes both valid paths into one terminal result and records the path,
identities, whether `PENDING` was observed, reconciliation attempt count, elapsed time, and terminal
status. The older `response.json` artifact is intentionally retired because a valid recovered path
may have no synchronous accepted response.

Failures use stable `stage` and `failureCode` fields rather than requiring automation to parse Java
exception class names. The hidden hand-off files `.exit-code`, `.ready`, and `.collected` are
implementation evidence and are not business assertions.

`verdict.json` is the outer cross-layer verdict. `verifier-verdict.json` is the typed Java
gRPC/Kafka verdict. A successful run requires both to report `PASS` and the normalized Admission
outcome to be terminal `ACCEPTED`.

On failure the harness also captures bounded Pod/workload inventory, recent Risk and Account logs,
the verifier Job description/logs, and the run ConfigMap. Diagnostic collection is best-effort and
must not overwrite the original failure.

## Current boundary

This retained verifier closes the missing deployed **accepted New Order** path, including the valid
Risk admission recovery path, and checks initial at-least-once duplicate consistency. It intentionally
does not yet claim the restart/replay portion of the complete RM-1 acceptance criteria. Before RM-1
is changed from `PARTIAL` to `COMPLETED`, extend the retained scenario with an explicit Risk/connector
restart or pause/resume boundary and prove that the same durable command identity preserves artifact
identity, partition, and command semantics after replay.

Do not mark RM-1 complete merely because this script exists. Status changes require a fresh canonical
local production-like run and retained evidence from the version of the verifier being claimed.

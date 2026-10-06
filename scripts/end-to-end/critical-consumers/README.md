# Critical consumer end-to-end certification

This directory contains deployed-system certification for the three critical
`matching.events` consumers: Persistence, Account, and QuickFIX Gateway.

The scripts exercise real Kubernetes workloads, Kafka, PostgreSQL, Kafka
Connect, and an external FIX session. They are not unit-test helpers and do not
call consumer implementation methods directly.

## Normal resting-buy acceptance contract (#162)

The smallest normal scenario submits one BUY / LIMIT / DAY order through a real
FIX session in a fresh, dedicated local namespace. It uses an eligible instrument
from the deployed daily artifact, one board lot, and a newly funded test account.
There is no opposing order, fault injection, replay, or session-close scenario.

`ORDER_RESTED` means the unfilled remainder entered the order book; it is not a
trade or merely an admission acknowledgement. A partially filled order can also
produce this event. This scenario therefore separately requires zero fills and
the complete original quantity still resting.

Acceptance requires one coherent chain of evidence:

1. The production live collector makes Gateway open-eligible; the operator calls
   the real `/operations/open` endpoint. No synthetic observation is posted and
   the independent freshness monitor and automatic close remain enabled.
2. The FIX `ClOrdID` resolves to exactly one accepted Risk admission, reservation,
   and new-order outbox row. Its stable command/order identities correlate with
   the actual `matching.commands` Kafka record and its admitted business fields.
   The Pending New ACK uses Gateway's `O-<ClOrdID>` WAL identity, not Risk's
   derived order UUID; correlation requires the same account and `ClOrdID`.
3. Matching publishes `ORDER_RESTED` for that order, account, instrument, side,
   price, quantity, session and artifact. Its source input offset identifies an
   observed byte-identical command delivery, not just an assumed topic position.
4. Persistence durably records `RESTING`, zero cumulative quantity, full leaves,
   the exact Matching event identity and payload digest, and no order fills.
5. Account durably holds exactly one accepted reservation for the same order:
   full remaining quantity, zero filled quantity, reserved notional equal to
   quantity times limit price, the same amount reserved in the daily account
   limit, and zero utilized notional. Both consumers process the exact event
   once, without quarantine. Kafka may physically redeliver identical bytes;
   this must not reserve or apply a business effect twice.
   The fresh namespace must have no active critical-consumer quarantine at all:
   an unrelated quarantine is also a real Gateway safety blocker, not ignored.
6. A single result contains only the necessary identifiers and business facts.
   No token, Secret value, raw FIX message, protobuf payload, or complete account
   payload is evidence. A PASS is published only after owned test overrides and
   helper processes/Pods are restored or removed successfully.

All workload and verifier images must come from the same committed source-aligned
deployment evidence. This proves one normal integration baseline, not a filled
trade, infrastructure-failure resilience, or a new `full-local` aggregate run.

## Structure

- `run-resting-buy-certification.sh` owns the normal #162 scenario; the readable
  `sql/` files and `lib/resting-buy-verification.rb` own its SQL observations and
  cross-boundary business assertions. The verifier tests diff a generated result
  against `tests/baselines/resting-buy-result.json` and reject corrupted facts.
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

## Run the normal scenario

Start one source-aligned deployment with the existing production-like runner,
retaining its disposable namespace. `--skip-compose` is sufficient for this
Kubernetes-only scenario: its infrastructure report is intentionally `PARTIAL`,
not a claim of `full-local` certification. Do not use `--matching-fleet-only`,
which lacks the required Gateway, Risk, Persistence and Account workloads.

```bash
test_trading_day=2026-08-27
delivery_manifest=tools/market-reference-builder/data/$test_trading_day/delivery/manifest.yaml
test -r "$delivery_manifest"

SIMPLEMATCH_CERTIFICATION_TRADING_DAY="$test_trading_day" \
SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST="$delivery_manifest" \
SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR=out/certification/issue-162-deployment \
  scripts/run-local-production-like-certification.sh \
    --skip-compose --keep-resources --image-transport kind-load

SIMPLEMATCH_CERTIFICATION_TRADING_DAY="$test_trading_day" \
SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR=out/certification/issue-162-deployment \
  scripts/end-to-end/critical-consumers/run-resting-buy-certification.sh \
    --namespace <retained-namespace> \
    --evidence-dir out/certification/issue-162-resting-buy
```

Use a clean committed tree, an empty result directory, and the canonical context.
Select an approved artifact for the explicit trading day before an expensive
build: this example intentionally uses the historical `2026-08-27` artifact,
not today's date. Keep that date consistent in deployment and observation; do
not relabel an old artifact or change the host clock. The runner refuses an
unrelated namespace, mismatched run ID, source revision or verifier image.
It also requires a completed deployment report and matching PASS results for
the trading prerequisites, including the fleet gate. A FAILED deployment is
not eligible even if its surviving Pods are Ready; the generated
`baseline/deployment-prerequisites.json` records this bounded prerequisite check.
It temporarily enables authenticated
operator HTTP access while preserving automatic close; it requires the existing
production live collector to make the gate open-eligible. Run before the configured
automatic close time. It does not relax readiness to compensate for a slow host.

The result is `verdict.json`, with the chain's command/order/event IDs, Kafka
positions, reserved amount, source revision and restoration result. Raw FIX stores
and logs live only in a private temporary directory and are removed on exit.
The deployment namespace remains owned by the deployment runner: the normal test
removes only its helper Pod, port-forwards, FIX process and Gateway overrides.

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

## Failure-scenario FIX submission boundary

In the failure scenario, the prepared FIX client logs on before the
freshness-sensitive admission window and waits for a release file. The runner
then supplies three fresh observations,
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

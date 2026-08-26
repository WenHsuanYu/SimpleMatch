# Critical Matching Event failure certification

This procedure is the final production-like failure test for the three critical
`matching.events` consumers tracked by issues #130, #131, and #132:
Persistence, Account, and QuickFIX Gateway.

It verifies one real FIX order through a controlled Matching, PostgreSQL, and
consumer outage. The test uses deployed Kubernetes workloads, Kafka,
PostgreSQL, Kafka Connect, and an external FIX session. It does not call
consumer implementation methods directly.

## Repository layout

The canonical test lives under:

```text
scripts/end-to-end/critical-consumers/
├── run-failure-certification.sh
├── lib/
│   ├── matching-status.sh
│   ├── system-observation.sh
│   ├── cluster-data.sh
│   ├── test-interfaces.sh
│   ├── failure-recovery.sh
│   └── failure-support.sh
└── tests/
    ├── matching-status-contract.sh
    ├── system-observation-contract.sh
    └── deployment-contract.sh
```

`scripts/run-critical-consumer-failure-certification.sh` remains as a
compatibility entrypoint and delegates to the canonical runner.

## Why the order is admitted before failure injection

The Gateway must accept new work only while required subsystems are healthy.
Stopping Matching before sending the FIX order would test an invalid scenario:
it would ask a fail-closed Gateway to accept work while Matching is unavailable.

The test therefore proves a different property: an order accepted while the
system is healthy remains durable and completes correctly when a failure occurs
immediately afterward.

## Gateway readiness observation

The Gateway operations HTTP interface is the existing operational interface for
opening new-order admission. The certification temporarily enables that HTTP
adapter and disables only the time-based market close. The stale-observation
monitor remains enabled throughout the test.

Each Gateway observation is assembled from explicit sources:

- Matching runtime `READY`, partition `OPEN`, and the Matching `observedAt`
  timestamp come from `runtime-metrics.json`.
- Matching durable command progress comes from Kafka consumer-group
  `CURRENT-OFFSET` for `matching-partition-consumer-N`.
- Kafka log-end positions come from `kafka-get-offsets.sh`.
- Persistence, Account, and QuickFIX durable progress comes from PostgreSQL
  consumer-progress tables.
- Risk, Account, Persistence, QuickFIX Gateway, and Kafka process availability
  comes from current Kubernetes workload status.

`runtime-metrics.json.next_commit_offset` is not a durable committed position.
It is only a pending commit candidate and normally becomes `null` after a
successful commit.

### Avoiding observation races

The verifier cannot obtain one atomic transaction across Kubernetes, Kafka, and
PostgreSQL. Instead it establishes a bounded observation window with three
explicit phases:

```text
capture opening Kafka positions
        |
        v
capture middle observations in parallel
  - Matching committed positions
  - critical-consumer durable progress
  - required Kubernetes workload state
  - all 15 Matching runtime samples
        |
        v
wait for every middle observation to complete
        |
        v
capture closing Kafka positions
        |
        v
require opening == closing
```

The opening and closing snapshots bracket every intermediate source read. The
closing Kafka snapshots are not started until all middle observations have
finished. This ordering is required for the stable-position check to mean what
it claims: if an opening and closing position are equal, the verifier has
established that the relevant Kafka log-end position did not move across the
bounded observation window.

This is not a distributed atomic snapshot. Kubernetes, Kafka, PostgreSQL, and
the Matching runtime do not participate in one transaction. The verifier only
establishes the narrower property needed by this certification: the relevant
Kafka positions remained stable while the cross-system evidence was collected.
If either Kafka position moves, the attempt is classified as
`KAFKA_POSITION_CHANGED`, discarded, and retried rather than reported as a
system failure.

Each Matching Pod is checked before and after reading runtime metrics. The Pod
UID and Ready state must remain unchanged, so a Pod replacement cannot combine
metadata from one process with runtime data from another.

Matching runtime freshness is checked against the Gateway stale-status policy.
The source timestamp from `updated_at_epoch_ms` is retained in the Gateway
observation instead of replacing it with the shell script's current time. The
Gateway freshness threshold remains unchanged; the certification must fit its
measurement work inside that policy rather than weakening production admission
semantics to accommodate a slow verifier.

### Observation timing evidence

Every collection attempt retains both `result.json` and `timing.json`, including
attempts that fail or are retried. `result.json` records the exit status,
retryability, a stable classification, and the diagnostic reason. Examples
include `KAFKA_POSITION_CHANGED`, `SOURCE_COLLECTION_FAILED`,
`EVIDENCE_TOO_OLD`, `MATCHING_NOT_READY`, and
`CONSUMER_PROGRESS_INCOMPLETE`.

`timing.json` separates the collection phases and records start, completion, and
duration values for the opening Kafka snapshots, each parallel middle source,
the closing Kafka snapshots, and validation. It also records the oldest and
newest Matching runtime source timestamps when available, the maximum fact age
allowed before submission, the oldest Matching runtime age at validation, and
the remaining freshness budget.

When an accepted observation is submitted to the Gateway, the same attempt also
records the HTTP submission start and completion times. The timing evidence then
reports the oldest Matching runtime source age and remaining freshness budget at
both ends of that HTTP call. The Gateway evaluates the request sometime within
that interval, so the two timestamps provide an explicit bound rather than
pretending the verifier knows the server's exact evaluation instant.

The timing data distinguishes different failure classes that would otherwise
look similar. A stale Matching source timestamp is different from a fresh source
that became too old because another observation took too long. A fact that was
fresh at validation but exhausted its budget during HTTP submission is also
visible directly. Likewise, Kafka movement is a measurement race and not
evidence that a production component is incorrect.

## FIX submission boundary

Starting Gradle, the JVM, QuickFIX/J, and the FIX Logon can take longer than the
Gateway freshness window. Those operations therefore happen before the Gateway
is opened.

The prepared FIX client performs:

```text
start client
→ FIX Logon succeeds
→ write ready file
→ wait for release file
```

The runner then performs:

```text
pause risk-service-outbox
→ collect three fresh OPEN_ELIGIBLE observations
→ open Gateway admission
→ release FIX client
→ Session.sendToTarget(NewOrderSingle)
```

The prepared client records the actual `Session.sendToTarget` time. The runner
compares it with the Gateway open operation's `occurredAt` value and requires the
order send to occur within two seconds after Gateway admission opens.

## Deterministic Risk outbox barrier

The Risk transaction and its transactional outbox row commit together. The
`risk-service-outbox` Kafka Connect connector publishes that durable command to
`matching.commands`.

The connector is paused before the FIX order is released. This allows Risk to
commit `ACCEPTED` while preventing the command from reaching Matching. The test
then proves that `matching.commands` has not advanced, stops Matching, resumes
the connector until exactly one command reaches the expected partition, and
pauses the connector again.

This establishes the failure boundary without relying on sleep timing.

## Failure and recovery sequence

```text
healthy production-like namespace
→ prepared FIX client logged on
→ Risk outbox connector paused
→ three fresh Gateway observations
→ Gateway OPEN
→ FIX order sent and Risk ACCEPTED
→ matching.commands still unchanged
→ Matching scaled to zero
→ accepted command released to Kafka
→ Account, Persistence, QuickFIX Gateway, PostgreSQL stopped
→ exact Matching Event observer started
→ Matching restored
→ exact command/order event observed in Kafka
→ PostgreSQL restored
→ Account, Persistence, QuickFIX Gateway restored
→ all three consumers process the exact event once
→ FIX delivery intent remains PENDING while client is offline
→ client reconnects and requests resend
→ QuickFIX Gateway restarts
→ same resend identity verified again
```

The Kafka observer matches both the canonical command ID and order ID. Unrelated
Kafka offset movement is not accepted as evidence.

## Baseline integrity

The namespace must be dedicated to this certification. Before admission, the
test requires:

- no active critical-consumer quarantine;
- no historical critical-consumer quarantine in the namespace;
- no pending FIX delivery intent;
- no active Matching order projection;
- all critical consumers caught up with `matching.events`.

The clean quarantine history supports the Gateway Kafka-integrity observation:
a payload conflict detected by the critical consumers would have produced a
quarantine rather than being reported as healthy.

## PASS and FAIL evidence

After the evidence directory has been initialized, both successful and failed
runs write `verdict.json`.

A successful result contains `status: PASS` and the command, order, event, FIX
execution, Kafka partition, event offset, and FIX sequence identities.

A failed result contains `status: FAIL`, the stage that failed, a reason, the
process exit status, and whether environment restoration failed. This prevents a
missing verdict file from hiding an earlier certification failure.

Observation attempts under `baseline/observation-*-attempt-*` retain their own
`result.json` and `timing.json`. These artifacts are diagnostic evidence for the
verifier itself and remain available even when an attempt is discarded before a
Gateway observation is submitted.

## Running the test

Use a disposable namespace that has already passed the local production-like
certification. The evidence directory must be empty.

Canonical command:

```bash
scripts/end-to-end/critical-consumers/run-failure-certification.sh \
  --namespace "$SIMPLEMATCH_CERTIFICATION_NAMESPACE" \
  --evidence-dir "$FAILURE_EVIDENCE_DIR" \
  --timeout-seconds 300
```

The historical command remains valid:

```bash
scripts/run-critical-consumer-failure-certification.sh \
  --namespace "$SIMPLEMATCH_CERTIFICATION_NAMESPACE" \
  --evidence-dir "$FAILURE_EVIDENCE_DIR" \
  --timeout-seconds 300
```

Issues #130, #131, and #132 should remain open until a fresh production-like
namespace produces a retained `verdict.json` with `status: PASS`.

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
│   ├── kafka-observation-interface.sh
│   ├── failure-recovery.sh
│   └── failure-support.sh
└── tests/
    ├── matching-status-contract.sh
    ├── system-observation-contract.sh
    ├── deployment-contract.sh
    ├── verdict-contract.sh
    └── verifier-helper-contract.sh
```

`scripts/lib/local-certification-provenance.sh` is shared with the local
production-like runner. It owns the retained source/image provenance consumed by
verification helpers.

`scripts/run-critical-consumer-failure-certification.sh` remains as a
compatibility entrypoint and delegates to the canonical runner.

## Retained production-like provenance

Failure certification is a continuation of one completed production-like run,
not a test that may attach to any disposable namespace. A successful full
production-like run records the repository revision and the exact verifier image
reference next to its existing `run-context` evidence. Registry transport stores
the digest-pinned verifier reference from `local-images.lock`; `kind-load`
records the canonical tagged image that was loaded into the kind nodes.

Before creating a verification helper, the failure certification requires all
of the following:

- the retained `run-context` namespace equals the requested namespace;
- `source-revision` equals the current repository `HEAD`;
- `verifier-image-reference` exists and is well formed.

The tracked helper manifests deliberately keep a canonical placeholder image.
For a certification run they are rendered into the failure evidence directory
with the retained verifier image reference. Both the warm Kafka observer and the
later exact Matching Event observer therefore use the same image that belongs to
the production-like run instead of assuming a node-local
`simplematch/risk-matching-e2e-verifier:local` image.

This check prevents mixed evidence such as a new verifier testing application
workloads that were deployed from an older commit. A production-like run created
before provenance recording was introduced is intentionally rejected and must
be recreated on the current source. If a Kafka observer Pod still fails to
become Ready, its Pod YAML, `describe` output, and container log are retained
under `diagnostics/kafka-observer-startup` before cleanup.

The production-like evidence directory defaults to
`out/certification/local-production-like`. If the retained run used another
directory, set `SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR` to that directory when
running failure certification.

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
- Matching durable command progress comes from Kafka consumer-group committed
  positions for `matching-partition-consumer-N`.
- Kafka log-end positions and Matching committed positions are read through one
  long-lived Kafka Admin client in the verification Pod.
- Persistence, Account, and QuickFIX durable progress comes from PostgreSQL
  consumer-progress tables.
- Risk, Account, Persistence, QuickFIX Gateway, and Kafka process availability
  comes from current Kubernetes workload status.

The Kafka observation process is started before freshness-sensitive collection.
Its JVM, Kafka client, connections, and topic metadata are therefore prepared
before the bounded observation window. Opening, committed, and closing Kafka
queries reuse that same process instead of starting `kafka-get-offsets.sh` or
`kafka-consumer-groups.sh` for every snapshot.

`runtime-metrics.json.next_commit_offset` is not a durable committed position.
It is only a pending commit candidate and normally becomes `null` after a
successful commit.

### Avoiding observation races

The verifier cannot obtain one atomic transaction across Kubernetes, Kafka, and
PostgreSQL. Instead it establishes a bounded observation window with ordered
phases:

```text
capture opening Kafka positions
        |
        v
capture durable observations in parallel
  - Matching committed positions
  - critical-consumer durable progress
  - required Kubernetes workload state
        |
        v
wait for durable observations to complete
        |
        v
capture all 15 Matching runtime samples
        |
        v
capture closing Kafka positions
        |
        v
require opening == closing
```

The opening and closing snapshots bracket every intermediate source read. The
Matching runtime samples are intentionally collected after the slower durable
observations because their source timestamps are freshness-sensitive. They still
occur before the closing Kafka snapshot, so moving them later does not weaken
the bounded observation window.

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
observation instead of replacing it with the collector's current time. The
Gateway freshness threshold remains 5000 ms. The certification continues to
reserve 1500 ms before submission until production-like timing evidence shows a
better reserve is justified; the production threshold is not weakened to make a
slow verifier pass.

### Observation timing and failure evidence

Every collection attempt retains both `result.json` and `timing.json`, including
attempts that fail or are retried. `result.json` records the exit status,
retryability, a stable classification, and the diagnostic reason.

`timing.json` records start, completion, and duration for the opening Kafka
snapshot, each durable observation, Matching runtime sampling, the closing Kafka
snapshot, validation, and Gateway submission when one occurs. Matching runtime
freshness evidence includes:

```text
oldestSourceAgeAtCaptureCompletionMillis
oldestSourceAgeAtValidationMillis
ageAddedByCollectorMillis
remainingBudgetAtValidationMillis
```

These values distinguish a source that was already stale when sampled from a
fresh source that expired while the collector completed the observation. The
corresponding classifications are `SOURCE_ALREADY_STALE` and
`EVIDENCE_EXPIRED_DURING_COLLECTION`.

`EVIDENCE_EXPIRED_DURING_COLLECTION` is still retryable once because transient
latency is possible. Two consecutive attempts with the same classification fail
fast instead of repeating the same collection five times. Other transient
conditions, such as Kafka movement or incomplete consumer progress, keep their
existing retry behavior.

When an accepted observation is submitted to the Gateway, the attempt records
HTTP submission start and completion times. The timing evidence reports the
oldest Matching runtime source age and remaining freshness budget at both ends
of that HTTP call. The Gateway evaluates the request sometime within that
interval, so these timestamps provide an explicit bound rather than assuming an
unobservable server evaluation time.

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
prepare warm Kafka observation process
→ pause risk-service-outbox
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

This establishes the failure point without relying on sleep timing.

## Failure and recovery sequence

```text
healthy production-like namespace
→ prepared FIX client logged on
→ warm Kafka observation process ready
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
→ final durable consumer state verified
→ temporary Gateway and workload changes restored
→ PASS verdict published
```

The Kafka event observer matches both the canonical command ID and order ID.
Unrelated Kafka offset movement is not accepted as evidence.

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
execution, Kafka partition, event offset, and FIX sequence identities. The PASS
payload is first written as `verdict.pending.json`. The cleanup path restores
temporary Gateway configuration, connector state, and workload replicas before
atomically publishing that file as `verdict.json`. A restoration failure removes
the pending PASS and writes a FAIL verdict instead, so retained PASS evidence
cannot describe a run that exited with an unrestored environment.

A failed result contains `status: FAIL`, the stage that failed, a reason, the
process exit status, and whether environment restoration failed. This prevents a
missing verdict file from hiding an earlier certification failure.

Observation attempts under `baseline/observation-*-attempt-*` retain their own
`result.json` and `timing.json`. Submitted attempts are archived under a
Gateway-attempt-specific path before a later retry can reuse the original path.

## Running the test

Use a disposable namespace from a production-like certification that completed
on the same repository revision. Retain that namespace with `--keep-resources`.
For the default evidence location:

```bash
bash scripts/run-local-production-like-certification.sh --keep-resources

export SIMPLEMATCH_CERTIFICATION_NAMESPACE="$(
  awk -F= '$1 == "namespace" {print $2}' \
    out/certification/local-production-like/run-context
)"
```

If the production-like run used a custom evidence directory, preserve that path
for the dependent failure certification:

```bash
export SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR=/path/to/production-like-evidence
```

The failure evidence directory must be new and empty. Canonical command:

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

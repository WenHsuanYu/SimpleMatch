# Query-service production-like certification

This gate proves the required read-only query capability against a fresh retained local
production-like deployment. It does not certify external production infrastructure. The runner
uses only a lifecycle-labelled disposable namespace whose retained evidence identifies the same
clean repository revision and immutable verifier image.

## Evidence contract

The retained Kafka stream must contain an execution-backed order whose Account summary and active
Market Reference row are queryable after the runner establishes its fixture. The runner rejects an
empty execution set; a resting order alone is not trade-view evidence. It selects one such fixture
from query-owned PostgreSQL,
then reads all business data through the public versioned API:

- order state and executions;
- Account lifecycle summary;
- active Market Reference trading day and artifact identity;
- source-topic partition offsets and recovery state.

The public runner establishes that prerequisite itself before selecting the fixture. It invokes the
repository-owned RM-1 verifier twice through the public Risk gRPC boundary: a BUY order provisions
an account cash limit and rests, then a SELL order provisions the same instrument's long position
and crosses the resting order. Both commands use the same retained artifact-selected instrument,
price, board lot, and partition; only their accounts and sides differ. The resulting evidence is
kept under `matching-fixture/buy/` and `matching-fixture/sell/`, and the runner waits for the final
Matching execution, Account lifecycle summary, and Market Reference joins to become visible in
query PostgreSQL. A caller therefore does not need to run a separate Matching script or hand-seed
an Account reservation row first; the only administrative fixture writes are the existing
account-authority cash-limit and long-position seeds required by the public reservation use case.

It captures the baseline, scales Redis to zero, and requires the same business view from the
query-owned PostgreSQL fallback. It then restores Redis, scales query-service to zero, records
that no query Pod exists, and captures the Risk admission, Account reservation, Persistence,
QuickFIX, and market-data projection state while the query API is actually unavailable. The
critical snapshot must remain unchanged through the quiet portion of this fault window. During the
outage it first runs a bounded quiescent-isolation probe (configurable with
`SIMPLEMATCH_QUERY_ISOLATION_PROBE_SECONDS`, maximum 30 complete samples) and a per-command
timeout (configured by `SIMPLEMATCH_QUERY_ISOLATION_COMMAND_TIMEOUT_SECONDS`, maximum 30 seconds).
The configured probe value requests complete samples with one-second gaps; it is not a hard
deadline for the shell collector. Kubernetes fault handling, Kafka committed-offset inspection,
and evidence materialization can legitimately make wall-clock elapsed time longer. Every external
command remains individually bounded, every requested sample must still pass all fail-closed
checks, and the evidence records the actual probe start, completion, elapsed milliseconds, and
sample indexes.
Each sample also records the exact Matching fleet topology: ordinals 0 through 14, current
StatefulSet revision and owner, Pod UID and node, the matching PVC and bound PV, and the PV
node-affinity mapping. Unowned or extra Matching Pods fail the probe before a verdict is produced.
Each sample confirms that query-service still has zero Pods, all 15 Matching partitions remain
Ready, every critical-path workload remains Ready with stable Pod identities and restart counts,
the critical-consumer state is healthy, and Matching committed offsets are present and
non-decreasing. This repeated operational observation proves that the outage caused no observed
health regression while the system was quiescent.

The runner then releases one prepared public FIX NewOrderSingle with `TimeInForce=IOC` while the
query deployment still has zero Pods. The FIX client is held at its ready barrier while the
quiescent outage probe runs; immediately before release, the Gateway is opened from three fresh
system observations. This placement keeps the final observation inside the five-second
`stale-status-after` window instead of allowing the probe to race the fail-closed safety monitor.
The submitted order uses a newly seeded account limit and the immutable artifact-selected
instrument. The active evidence must correlate the FIX acceptance, durable Risk admission, routed
partition, exact `MATCHING_EVENT_TYPE_ORDER_CANCELLED`, terminal Persistence
projection, market-data projection progress and inbox, all three critical-consumer inboxes, and a
sent QuickFIX terminal delivery intent. It also requires zero quarantine history, zero pending FIX
intents, and zero active Matching orders after the event. The IOC shape is intentional: with no
opposite resting order it creates one deterministic terminal event without leaving state that would
contaminate the later replay comparison. The active check fails closed when any identity, event
offset, terminal status, inbox count, or query-outage assertion is absent. The Matching Event
observer records both the configured Kafka seek start offset and the matched record offset, so the
verdict can prove that the event came from the intended observation range. The critical-consumer
snapshot also carries each market-data partition's `last_processed_offset` and `recovery_state`,
so active progress is compared with the observed event offset rather than inferred from a summary
counter. Query-service is restored
only after this active proof and before the normally disabled authenticated reset adapter is enabled;
the reset clears only query-owned rebuildable state. Immediately before the reset, it records the
15-partition Kafka high-water
offset for each source in `replay-boundary/manifest.json`. The reset input starts each partition at
the captured earliest offset and the rebuilt run must commit exactly the recorded high-water offset;
this prevents a moving `--to-earliest` replay from silently consuming events added after the
certification boundary. The runner resets these two groups to the retained boundary:

- `query-service-matching-events` on `matching.events`;
- `query-service-account-lifecycle` on `account.lifecycle`.

No critical consumer offset is changed. A rebuilt snapshot is eligible for comparison only after
both query groups reach their captured offsets exactly and every disclosed checkpoint is `READY`.
Public reads must then recreate the four exact versioned Redis keys selected by the fixture. The
final verdict requires the expanded critical snapshots to remain identical before and during the
quiescent portion of the query outage, all 15 Matching partitions to remain ready, all
critical-path workload health samples to remain stable, the active public IOC event proof to pass,
and the query deployment to be restored. The active event is allowed to change authoritative
consumer counters after the quiescent snapshot; those post-event changes are checked through the
correlated active evidence instead of being misclassified as query-service isolation failure.

The deterministic-rebuild comparison covers business fields only. Projection observation and
artifact-install timestamps are operational metadata and may change during replay; the separate
freshness response still proves that every source checkpoint is present, contiguous, and `READY`.

## Run the gate

First create a fresh production-like run at the committed source revision and retain its namespace:

```bash
bash scripts/run-local-production-like-certification.sh --keep-resources
```

Then provide that run's namespace and evidence directory explicitly:

```bash
bash scripts/run-query-service-certification.sh \
  --namespace "${SIMPLEMATCH_CERTIFICATION_NAMESPACE}" \
  --retained-evidence-dir "${SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR}" \
  --evidence-dir "${SIMPLEMATCH_QUERY_CERTIFICATION_EVIDENCE_DIR}"
```

The output directory must be empty. On success, `verdict.json` contains a `PASS` result and the
individual deterministic-rebuild, PostgreSQL-fallback, freshness, market-identity, Redis-rebuild,
service-restoration, active-processing-liveness, quiescent-critical-path-isolation, and
critical-path-isolation checks. Baseline, active-event, outage, probe samples, rebuilt,
consumer-state, offset-reset, provenance, deployment, and diagnostic evidence remain beside it.

## Safety and restoration

The runner refuses the Docker Desktop context, an ordinary namespace, stale source provenance, a
different retained namespace, or pre-existing rebuild environment overrides. It restores the
original Redis and query-service replica counts, removes only the two temporary query rebuild
environment variables, stops the public FIX/Gateway/Kafka observer clients, removes only the
run-owned observer Pods, restores the Gateway environment, and stops its port-forwards. A command
failure or restoration failure emits a failing verdict instead of silently accepting partial
evidence.

Retained resources still belong to the production-like lifecycle. Clean them up with that
workflow's repository helper; do not delete the namespace directly and do not use broad Docker or
Kubernetes prune commands.

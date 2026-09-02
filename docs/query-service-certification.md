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
critical snapshot must remain unchanged through this fault window. Query-service is restored before
the normally disabled authenticated reset adapter is enabled and only query-owned rebuildable state
is cleared. Immediately before the reset, it records the 15-partition Kafka high-water
offset for each source in `replay-boundary/manifest.json`. The reset input starts each partition at
the captured earliest offset and the rebuilt run must commit exactly the recorded high-water offset;
this prevents a moving `--to-earliest` replay from silently consuming events added after the
certification boundary. The runner resets these two groups to the retained boundary:

- `query-service-matching-events` on `matching.events`;
- `query-service-account-lifecycle` on `account.lifecycle`.

No critical consumer offset is changed. A rebuilt snapshot is eligible for comparison only after
both query groups reach their captured offsets exactly and every disclosed checkpoint is `READY`.
Public reads must then
recreate the four exact versioned Redis keys selected by the fixture. The final verdict also
requires the expanded critical snapshots to remain identical before, during, and after the query
outage, all 15 Matching partitions to remain ready, and the query deployment to be restored.

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
service-restoration, and critical-path-isolation checks. Baseline, outage, rebuilt, consumer-state,
offset-reset, provenance, deployment, and diagnostic evidence remain beside it.

## Safety and restoration

The runner refuses the Docker Desktop context, an ordinary namespace, stale source provenance, a
different retained namespace, or pre-existing rebuild environment overrides. It restores the
original Redis and query-service replica counts, removes only the two temporary query rebuild
environment variables, and stops its port-forward. A command failure or restoration failure emits
a failing verdict instead of silently accepting partial evidence.

Retained resources still belong to the production-like lifecycle. Clean them up with that
workflow's repository helper; do not delete the namespace directly and do not use broad Docker or
Kubernetes prune commands.

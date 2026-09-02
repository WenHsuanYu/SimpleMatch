# Query-service production-like certification

This gate proves the required read-only query capability against a fresh retained local
production-like deployment. It does not certify external production infrastructure. The runner
uses only a lifecycle-labelled disposable namespace whose retained evidence identifies the same
clean repository revision and immutable verifier image.

## Evidence contract

The retained Kafka stream must already contain an execution-backed order whose Account summary and
active Market Reference row are queryable. The runner rejects an empty execution set; a resting
order alone is not trade-view evidence. It selects one such fixture from query-owned PostgreSQL,
then reads all business data through the public versioned API:

- order state and executions;
- Account lifecycle summary;
- active Market Reference trading day and artifact identity;
- source-topic partition offsets and recovery state.

It captures the baseline, scales Redis to zero, and requires the same business view from the
query-owned PostgreSQL fallback. It then restores Redis, reduces query-service to one consumer
owner, enables the normally disabled authenticated reset adapter, and clears only query-owned
rebuildable state. The runner resets these two groups to the retained boundary:

- `query-service-matching-events` on `matching.events`;
- `query-service-account-lifecycle` on `account.lifecycle`.

No critical consumer offset is changed. A rebuilt snapshot is eligible for comparison only after
both query groups report zero lag and every disclosed checkpoint is `READY`. Public reads must then
recreate the four exact versioned Redis keys selected by the fixture. The final verdict also
requires the critical Persistence, Account, and QuickFIX state snapshots to remain identical and
all 15 Matching partitions to remain ready.

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

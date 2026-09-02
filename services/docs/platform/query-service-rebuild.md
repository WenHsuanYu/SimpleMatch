# Query-service projection replay

`query-service` owns rebuildable PostgreSQL read models and a disposable Redis cache. Neither is
trading authority. A replay resets only query-owned state and the two query consumer groups; it
must not modify Matching, Persistence, Account, QuickFIX, or admission state.

The reset HTTP adapter is disabled by default. Enable it only for a controlled operator run with
`simplematch.query-service.rebuild.http-enabled=true` and an externally supplied
`simplematch.query-service.rebuild.operator-token`. The
`X-SimpleMatch-Query-Token` header protects the adapter.

Use this bounded procedure:

1. Reduce `query-service` to one replica so one process owns both consumer groups. Call
   `POST /internal/query/rebuild` with the operator header. The adapter stops the Matching and
   Account listeners before one local transaction clears the query-owned inbox, checkpoints, read
   models, active Market Reference rows, and Redis namespace.
2. Verify both listeners are stopped. Reset only `query-service-matching-events` on
   `matching.events` and `query-service-account-lifecycle` on `account.lifecycle` to the retained
   replay boundary. Do not change any critical consumer group.
3. Restart `query-service`, wait for both groups to reach the captured end offsets, and verify the
   order, execution, account-summary, active-market-reference, and freshness responses against the
   pre-reset baseline.
4. Restore the original replica count and disable the reset adapter.

The endpoint response proves only that the service-owned reset transaction completed. Kafka offset
reset, source retention, replay boundaries, deterministic responses, Redis convergence, and final
lag remain operator-owned evidence. Use the repository production-like certification workflow to
retain that evidence; never treat the HTTP response alone as a completed replay.

The repository-owned completion gate is
[`docs/query-service-certification.md`](../../../docs/query-service-certification.md). It requires
an explicit fresh retained production-like evidence directory:

```bash
bash scripts/run-query-service-certification.sh \
  --namespace "${SIMPLEMATCH_CERTIFICATION_NAMESPACE}" \
  --retained-evidence-dir "${SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR}" \
  --evidence-dir "${SIMPLEMATCH_QUERY_CERTIFICATION_EVIDENCE_DIR}"
```

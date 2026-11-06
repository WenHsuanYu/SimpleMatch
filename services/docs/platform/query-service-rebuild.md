# Query-service projection replay

`query-service` owns rebuildable PostgreSQL read models and an optional disposable Redis adapter.
The four business reads currently bypass response caching; neither store is trading authority.
A replay resets only query-owned state and the two query consumer groups; it
must not modify Matching, Persistence, Account, QuickFIX, or admission state.

The reset HTTP adapter is disabled by default. Enable it only for a controlled operator run with
`simplematch.query-service.rebuild.http-enabled=true` and an externally supplied
`simplematch.query-service.rebuild.operator-token`. The
`X-SimpleMatch-Query-Token` header protects the adapter.

The durable reset transaction has an explicit eight-second timeout; timeout or database failure
rolls back the PostgreSQL reset and fails the operation before Redis cleanup.

Use this bounded procedure:

1. Reduce `query-service` to one replica so one process owns both consumer groups. Call
   `POST /internal/query/rebuild` with the operator header. The adapter stops the Matching and
   Account listeners before one local transaction clears the query-owned PostgreSQL inbox,
   checkpoints, read models, and active Market Reference rows. After that commit it clears the
   disposable Redis namespace; a Redis failure is an operator retry/reconciliation outcome and
   does not roll back the durable reset.
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

The repository-owned deployed verification gate is
[`docs/verification/query-service-certification.md`](../../../docs/verification/query-service-certification.md).
Issue #202 retired the standalone Shell replay certification. The selected
Jupiter scenario now owns the source-bound, run-owned reset and replay:

```bash
./gradlew verifyKubernetes -PkubernetesScenario=resting-buy --no-daemon
```

This selected test creates its own disposable identities and uses a separate
operator credential. It checks that reader and operations credentials cannot
reset Query. It restores the original Query policy and replica count after
successful replay. See [the historical collector integration](../query-service/certification-integration.md)
for the original #194 evidence and input-security boundaries.

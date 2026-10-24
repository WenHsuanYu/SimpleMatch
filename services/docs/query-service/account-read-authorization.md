# Account Summary authorization — QS1-02

The subsequent [QS1-03 Order slice](order-read-authorization.md) supersedes this first Account
slice's Order-route denial. The #189 verification record retains its original scope and source.

Scope: [#189](https://github.com/WenHsuanYu/SimpleMatch/issues/189), one approved synthetic Local
Lab slice after [opaque authentication](opaque-authentication.md). Account Summary is now an
authorized PostgreSQL-only business read. Order, Executions and Market remain denied until their
own slices. This is not OAuth, Gateway, transport security, deployed revocation or production
account-governance acceptance.

## Identity, authorization and resource integrity

The existing Spring Security chain authenticates the explicit Bearer credential and establishes
the registered `QueryPrincipal`. Only exact GET `/api/v1/accounts/{accountId}/summary` is enabled
by this slice. Authentication runs before Controller argument processing; malformed identifiers
do not bypass authentication. HEAD, OPTIONS and other unapproved methods remain denied.

The account path accepts a canonical lowercase UUID. It selects a resource and carries no
authority. `QueryAuthorizedReadService` checks the principal's immutable explicit Account Grants
before opening a database transaction. Operations permission and account ownership of another
principal do not add grants. A returned projection must also contain the authorized Account ID;
an inconsistent database reply fails closed rather than exposing the different account.

| Condition | Response |
| --- | --- |
| Missing, invalid, expired or disabled authentication | 401 with the existing Bearer challenge |
| Authenticated request with a noncanonical/malformed Account UUID | 400, empty body |
| Account not granted, or granted Account absent from the projection | 404, empty body |
| Authorized existing Account | 200 with the minimal business envelope |
| Required database/transaction unavailable, or inconsistent projection identity | 503, empty body |
| Unapproved method with valid Query authentication | 403 under the default-deny route policy |

The HTTP security headers retain `Cache-Control: no-store`; identity/account headers supplied by
the caller have no effect. Query credentials do not acquire rebuild authority, and the separate
operator credential does not establish a Query principal. The read-only exception handler applies
to the two Query read Controllers, preserving the independent rebuild operation's contract.

## Business meaning and response compatibility

The data remains the latest applied reservation-related Account Lifecycle projection. It is not
an account-wide balance, authoritative available trading capacity, a per-account business revision
or a monotonic-read guarantee. This slice does not change event ordering, aggregation or Account
Authority's domain model. See the separate [Account semantics review](account-summary-semantics-review.md).

The repository has no published external API compatibility promise. Business v1 envelopes are
revised in place to `data` plus the following minimal diagnostics; no v2 or dual DTO is introduced:

```json
{
  "data": {
    "lifecycleState": "ACCOUNT_LIFECYCLE_STATE_RESERVED",
    "reservedNotionalUnits": 1000
  },
  "freshness": {
    "projectionState": "NO_GAP_RECORDED"
  }
}
```

The example abbreviates the existing business fields. `QueryReadResponse.fromProjection` owns
the shared minimal envelope. Any known `GAP_DETECTED` checkpoint takes precedence and produces
`RECOVERY_REQUIRED`. Otherwise absent/empty diagnostics or any unsupported/missing state produce
`UNKNOWN`. Only a nonempty set containing exclusively `READY` produces `NO_GAP_RECORDED`.

These states describe only recorded checkpoints. They provide no source-coverage, current Kafka
boundary, source lag, `asOf` or business-version claim. General business responses no longer carry
topics, partitions, offsets or raw recovery metadata. Full checkpoints remain available through
the independently protected operations-read `/api/v1/freshness` endpoint.

## PostgreSQL snapshot and cache exclusion

After the grant decision, the application module uses a short read-only `REPEATABLE READ`
transaction for both the existing indexed Account lookup and checkpoint lookup. It starts its
own transaction rather than joining a caller's weaker snapshot. The existing projection writer
continues atomically applying its inbox, data and checkpoint through its transaction-owning
application module; no schema, event contract or cross-service database access changes are needed.

Spring's [programmatic transaction support](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
keeps this transaction narrower than the full authorization method. PostgreSQL's
[Repeatable Read contract](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-REPEATABLE-READ)
provides the shared observation across these SELECTs. A deterministic PostgreSQL test commits a
writer between the two reads and verifies the old response remains coherent while the next request
sees the new data and state. `readOnly` alone is insufficient, as the retained red test demonstrates.

The five-second transaction timeout is a Local Lab budget, not an HTTP request deadline or
revocation SLA. Serialization happens after the transaction. Neither Redis nor another remote
service is awaited while holding the database transaction/connection.

The Account HTTP Controller and application module have no cache dependency. Both reads and fills
are bypassed, including previously stored full envelopes and foreign-account payloads. Old keys
are retained; this slice does not flush Redis. A missing grant, missing projection, required
database failure or identity mismatch cannot fall back to a cached response. Reintroduction of
Account caching remains conditional on Phase 2 consistency and performance gates.

## Verification and evidence limits

- Real Spring Security HTTP tests cover the approved A→X, B→Y, C→X/Y, Operations and Client D
  matrix, authentication precedence, canonical UUIDs, forged headers, concealed 404, methods,
  no-store, database failure and inconsistent returned identity.
- An external-cache probe is preseeded with obsolete full envelopes and mismatched Account IDs.
  HTTP tests require zero cache reads, fills or clears. This proves application cache-port
  exclusion; it is not a live Redis/TLS deployment test.
- A separate V2 application context preserves synthetic principal/credential identity, removes
  only C→Y and disables D. With old cache entries present, C→Y is denied, C→X and B→Y still work,
  and D cannot authenticate. This is policy-snapshot correctness, not proof that old replicas or
  in-flight requests stopped serving. [#193](https://github.com/WenHsuanYu/SimpleMatch/issues/193)
  retains deployment activation, anti-rollback and effective revocation ownership.
- The public envelope factory has focused tests for empty, absent, READY, gap, unsupported and
  mixed state sets, including gap precedence and the exact minimal JSON shape.
- Ordinary tests use migrated H2. The existing `postgresJdbcTest` additionally includes both new
  HTTP classes; its concurrent snapshot case runs only with the PostgreSQL backend. Each HTTP
  fixture clears its runner-owned projection data after the test, including failure scenarios.

```bash
./gradlew -q :services:query-service:test
./gradlew -q :services:query-service:postgresJdbcTest
./gradlew -q staticAnalysis
```

The PostgreSQL task requires the existing private `QUERY_SERVICE_JDBC_TEST_*` environment and
ownership marker; never aim it at an application database. See the authentication runbook for
the fixture contract. Actual outcomes, source fingerprints, retained failures and cleanup are
recorded in the issue handoff and `out/verification/issue-189-account-summary/`.

## Certification consumers and historical evidence

This decision supersedes #137's Account response-cache expectation and full business-envelope
checkpoint disclosure for the newly secured path. It does not invalidate or rewrite the historical
#137/#138 results. The standalone full `/freshness` response and projection/replay diagnostics
remain intact.

The current shell collector obtains its aggregate `freshness.partitions` from the separate
operations endpoint; that checkpoint validation still has meaning. Credential-aware collection,
approved fixture selection, remaining business routes and revised Account-cache expectations
belong to [#194](https://github.com/WenHsuanYu/SimpleMatch/issues/194). Existing anonymous aggregate
certification is not restored by weakening the route policy, nor claimed to pass in this slice.

No Kubernetes rollout, incoming TLS, multi-replica convergence, hard revocation SLA, source catch-up,
authoritative trading capacity, cache optimization or final Phase 1 certification is claimed here.

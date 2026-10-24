# Execution collection authorization — QS1-04

Scope: [#191](https://github.com/WenHsuanYu/SimpleMatch/issues/191), one approved synthetic Local
Lab slice after [Order authorization](order-read-authorization.md). Exact GET
`/api/v1/orders/{orderId}/executions` is enabled. Market, deployed policy maintenance and final
collector certification remain owned by #192–#194. This adds no schema, identity authority,
configuration source, deployment or synchronous trading-path dependency.

## Authentication and the trusted parent boundary

Spring Security establishes the registered immutable `QueryPrincipal` before UUID parsing. The
canonical lowercase Order UUID selects a resource; headers, query parameters, identifier possession,
operations permission and rebuild credentials do not establish its ownership or grant access.

`QueryAuthorizedReadService` shares the existing parent lookup/identity/grant decision between
Order and Executions. It validates the durable parent's Order ID and canonical Account ID, then
checks that Account against the authenticated policy snapshot. Only an authorized existing parent
permits the collection lookup. Neither an orphaned execution nor a Redis entry proves existence.

Every returned execution must match both the requested Order and the authorized parent's Account.
The module checks the complete collection before constructing the immutable response. One bad row
rejects the entire result with an empty 503; rows are never silently filtered into partial success.
Even a principal granted both Accounts cannot read a mixed-account or mixed-order collection.
The existing indexed SQL preserves `executed_at_unix_ms, execution_id` order.

## Snapshot and HTTP contract

Parent ownership, collection and recorded diagnostics use the same short read-only
`REQUIRES_NEW` / PostgreSQL `REPEATABLE READ` transaction. The five-second transaction budget
is a Local Lab parameter, not a whole-request timeout or revocation deadline. Responses are
materialized before leaving the transaction; no Redis operation holds a database connection.

| Condition | Result |
| --- | --- |
| Missing/invalid/disabled Query credential | 401, appropriate Bearer challenge |
| Valid authentication and malformed/noncanonical Order UUID | 400, empty body |
| Missing parent or no grant to its durable Account | 404, same empty body |
| Authorized existing parent with no executions | 200, `data: []` |
| Authorized existing parent with consistent executions | 200, complete collection |
| Required database/transaction unavailable or inconsistent durable identities | 503, empty body |
| Unapproved method with valid authentication | 403 under default denial |

Responses retain `Cache-Control: no-store`. Concealment covers status/body, not equal timing.
Unknown child routes remain denied. No Basic, Form Login, cookie session or fallback credential
mechanism is added. Rebuild retains its separate operator trust boundary.

Business responses use `data` plus minimal `freshness.projectionState`. `NO_GAP_RECORDED` means
only a nonempty all-READY recorded set; `RECOVERY_REQUIRED` records a known gap; `UNKNOWN` makes
no affirmative claim. Full topics/partitions/offsets remain on operations-read `/freshness`.
There is no source-coverage, catch-up, as-of, monotonic-read, immutable-ownership or authoritative
trading-capacity/balance guarantee.

An already accepted snapshot may finish using its previously observed owner. PostgreSQL tests
commit parent/child changes after the parent read, and deletion plus diagnostic changes after the
collection read. The in-flight response must keep the old authorized parent, payload and summary;
later requests observe the new ownership or missing parent. This is snapshot-time consistency,
not immediate ownership-change revocation or multi-replica policy convergence.

## Cache and compatibility boundary

The old cache-returning Executions handler is removed. The authorized adapter/module has no
response-cache dependency and performs no execution cache reads, fills or broad clears. Old keys
remain untouched. Foreign/stale full envelopes cannot restore data or checkpoints, and missing or
unavailable ownership cannot use a warm-cache fallback. Selective typed caching requires Phase 2.

There is no published external compatibility promise, so the existing v1 contract changes in place.
Clients require Query credentials and the parent's explicit Account Grant. Missing parents now
return 404 instead of an empty success. The old standalone malformed-UUID test moves to actual
FilterChain HTTP coverage; old default-deny assertions are narrowed to still-unapproved routes.

Historical #137 execution-cache/full-checkpoint expectations are superseded for this secured path;
its original evidence and closed state remain intact. #138 is a separate platform-security scope.
#194 owns credential-aware collectors, placement observers and final integrated expectations.
The denied legacy Market adapter and its Redis TLS/ACL enforcement are unchanged here.

## Requirement-to-test traceability

Tests use actual Spring Security HTTP filters, migrated owned Query tables and the agreed external
JDBC/cache failure probes. They do not mock private application methods or internal modules.
Synthetic fixture DML proves read behavior, not Kafka generation/replay or upstream correctness.

| Requirement | Implementing boundary | Evidence required in this slice |
| --- | --- | --- |
| INV-01/03/05/06; A4 | Exact GET rule, authenticated principal, canonical selector | `authenticatesBeforeValidatingTheParentUuid`, forged-selector and method tests; existing direct HTTP/rebuild regressions |
| INV-02; A1 parent | Shared durable parent/grant decision | Five principals × four executed/empty X/Y Orders; verified absent parent; no-grant Operations/D; multiple grants C |
| INV-02/03; A1 collection | Validate every Order/Account before response | Mixed-account durable rows and one substituted mixed-order JDBC reply alongside valid rows each produce empty 503; valid multirow deterministic result |
| INV-04; A1/R2 | No execution response-cache dependency | Foreign/stale full envelopes, missing/unavailable parent and V2 grant denial; cache probe records zero GET/PUT/CLEAR |
| INV-07 application scope; A3 | Separately activated immutable V2 snapshot | C→Y denied, C→X and B→Y preserved; disabled D rejected with warm execution cache |
| CON-05; minimal envelope | Parent, collection and summary in one snapshot | PostgreSQL `QueryExecutionsSnapshotHttpTest` update/deletion interleavings; no full checkpoint/as-of fields |
| Failure/default denial | Existing sanitized read advice and security chain | Connection failure/mismatched parent/mixed children return empty 503; unknown routes remain denied |

V2 context tests prove application enforcement only. Deployed revocation start/block/drain/effective/
resume, old replicas/connections, anti-rollback and no-reset catch-up remain #193/#194. No inbound
TLS, Gateway, OAuth, certificate binding, Redis performance or production-readiness claim follows.

```bash
./gradlew -q :services:query-service:test :services:query-service:bootJar
./gradlew -q :services:query-service:postgresJdbcTest
./gradlew -q staticAnalysis
```

The PostgreSQL task requires the existing private `QUERY_SERVICE_JDBC_TEST_*` environment and
database ownership marker. Ordinary tests use H2 and explicitly skip PostgreSQL-only concurrency
cases. Runtime counts, input fingerprints, original red tests, review, cleanup and final-head CI
are recorded in the #191 handoff; do not infer a PASS from this runbook.

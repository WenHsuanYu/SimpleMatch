# Order authorization — QS1-03

This note records the #190 slice. The subsequent [Market slice](market-read-authorization.md)
removes the last legacy cache handler and replaces its obsolete Controller fallback tests while
retaining the independent Redis TLS/ACL gate. See the [current route policy](README.md).

Scope: [#190](https://github.com/WenHsuanYu/SimpleMatch/issues/190), one approved synthetic Local
Lab slice after [Account Summary authorization](account-read-authorization.md). Exact GET
`/api/v1/orders/{orderId}` is enabled. [Executions](execution-read-authorization.md) subsequently
reuses this parent boundary in #191; Market and later phases keep their separate gates.
This change introduces no new identity authority, schema, dependency version or deployment.

## Authentication, ownership and authorization

The existing Spring Security chain establishes the registered immutable `QueryPrincipal` before
Controller processing. Canonical lowercase UUID validation then selects the requested Order.
Neither the identifier nor client-supplied account/principal headers or query parameters grant
access. Operations permission and the independent rebuild credential do not add Account Grants.

`QueryAuthorizedReadService.order` opens the established short read-only
`REQUIRES_NEW` / `REPEATABLE READ` transaction and calls the existing indexed
`QueryProjectionStore.findOrder` lookup. The full durable row provides both ownership and business
details. The application module validates the returned Order ID, validates its canonical Account
ID, and requires an explicit grant to that Account before constructing a response. Freshness is
read inside the same transaction only after the grant decision succeeds.

Using one row for ownership and details avoids a separate authorization/detail lookup race and an
extra ownership SELECT. The PostgreSQL row is the trusted Query-owned ownership source; Redis is
never involved. This does not establish an immutable business ownership contract or introduce a
second independent ownership authority. A different coherent durable owner requires that owner's
Account Grant. A mismatched returned Order ID or malformed durable identity is an integrity error.

## Snapshot and failure contract

| Condition | HTTP result |
| --- | --- |
| Missing, invalid, expired or disabled Query authentication | 401 |
| Valid authentication with malformed/noncanonical Order UUID | 400, empty body |
| Order absent, or its durable Account not granted | 404, empty body |
| Existing Order with the required Account Grant | 200, shared minimal envelope |
| Required database/transaction unavailable or inconsistent returned identity | 503, empty body |
| Unapproved method with valid Query authentication | 403 under default denial |

Sensitive responses retain `Cache-Control: no-store`. Denied and missing Orders have the same
status/body contract without foreign identifiers; no timing-equivalence guarantee is claimed.
The Order rule alone does not enable child routes. #191 adds a separate reviewed Executions rule;
unknown child routes remain denied.

Ownership, details and recorded diagnostics describe one PostgreSQL observation. The deterministic
concurrent-writer test commits a changed owner, Order state and checkpoint while an earlier read
is paused. The earlier response keeps its old authorized owner/data/diagnostics; subsequent reads
deny the old Account's principal and permit a principal granted the new owner. It never mixes new
Account data into an old authorization decision.

An in-flight snapshot can therefore finish with its previously observed owner. This is not
immediate ownership-change revocation, a cross-request monotonic-read guarantee, or proof that
the projection cannot change ownership. Controlled grant-policy revocation and old-replica/
in-flight isolation remain owned by [#193](https://github.com/WenHsuanYu/SimpleMatch/issues/193).
The existing five-second transaction budget is not an HTTP deadline or production revocation SLA.

## Cache and response compatibility

The Order adapter and authorized-read module have no response-cache dependency. Both Order cache
reads and fills are bypassed; old keys are retained rather than flushed. Missing/unavailable
ownership and failed authorization cannot use a cached response. Preseeded foreign and stale
full envelopes cannot affect the HTTP result, including when the database is unavailable.

Responses use the minimal v1 `data` plus `freshness.projectionState` envelope introduced by #189.
Known gaps produce `RECOVERY_REQUIRED`; absent/unsupported diagnostics produce `UNKNOWN`; only a
nonempty all-READY recorded set produces `NO_GAP_RECORDED`. Full checkpoints remain restricted to
the operations-read `/freshness` endpoint. These summaries do not prove source coverage, catch-up,
current admission authority, business revision or an authoritative trading limit/balance.

The repository has no published external compatibility promise. The old cache-returning Order
handler is removed. Registered clients now require the specific Account Grant instead of relying
on identifier possession or the former anonymous read contract. Canonical URI parsing is shared
with Account Summary without changing its accepted behavior. No anonymous compatibility mode or
new API version is added.

Existing standalone fallback tests move to the still-denied legacy Market adapter so their cache
failure purpose remains covered. Its TLS/ACL gate remains an adapter/network test; it does not
authorize Market HTTP or prove inbound Query TLS. Order-cache certification expectations from
historical #137 are superseded for the secured path. Historical evidence and closed #137/#138
remain intact; [#194](https://github.com/WenHsuanYu/SimpleMatch/issues/194) owns credential-aware
collectors and integrated contract replacement once all business routes are complete.

## Fixture and verification contract

Use the existing five-principal policy and synthetic Accounts X/Y. Each Account has one
`*-with-executions` Order and one `*-empty` Order. The missing-parent UUID is never inserted and
its absence is asserted before every scenario. Fixture initialization uses DML against migrated,
owned Query tables; it does not certify Matching event generation or Kafka replay.

The fixture check verifies Order ownership and Execution-to-Order/Account relationships through
the HTTP read and existing projection port, then records only nonsecret canonical IDs. This does
not enable Executions HTTP in #190; #191 independently adds its authorization. Corruption and ownership-update scenarios
are isolated variants, not part of the valid baseline.

HTTP tests cover all five principals across the four Orders, multiple grants, forged selectors,
strict authentication/UUID precedence, same concealed 404, dependency/integrity failures, minimal
diagnostics, methods, warm cache and route isolation. A separate activated V2 context proves C→Y
denial while C→X and B→Y remain valid, and disabled D cannot use old cached Order data. This is
application policy-snapshot evidence, not multi-replica convergence or effective revocation.

The existing JDBC reply/concurrency and external cache probes are shared with Account tests.
They remain test-only; no private application methods or internal collaborators are mocked.
Ordinary and focused `postgresJdbcTest` database tests share the owned PostgreSQL fixture and
canonical migrations. Account and Order concurrent-writer cases execute in both tasks. Both tasks
require Docker; the focused task additionally runs the Python collector contract.

```bash
./gradlew -q :services:query-service:test :services:query-service:bootJar
./gradlew -q :services:query-service:postgresJdbcTest
./gradlew -q staticAnalysis
./gradlew -q :services:query-service:redisSecurityTest
```

The PostgreSQL task requires Docker, Python 3, and `psql`.
Testcontainers creates an isolated database. The test fixture sets its ownership marker before Flyway migrations or fixture writes.
The task supplies private connection settings to the existing Python collector contract.

The Redis task requires Docker and OpenSSL.
It generates private certificate and ACL files. Testcontainers manages the disposable Redis containers.
See the [Redis test requirements](../../../deploy/k8s/README.md#query-redis-transport-and-acl-contract).

Actual
commands, native/container fixture identities, fresh reports, source fingerprints and cleanup are
retained under `out/verification/issue-190-order/` and summarized in the issue handoff.

No Gateway, incoming TLS, OAuth, certificate binding, production rollout, cache optimization,
performance threshold, authoritative account capacity or final Phase 1 certification is claimed.

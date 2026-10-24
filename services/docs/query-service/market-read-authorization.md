# Authenticated Market Reference — QS1-05

Scope: [#192](https://github.com/WenHsuanYu/SimpleMatch/issues/192), one synthetic Local Lab slice.
Exact GET `/api/v1/market-reference/{tradingDay}/{venueMic}/{symbol}` serves installed artifact
facts directly from Query-owned PostgreSQL. This is not approval to publish Market Reference
externally. Deployed revocation, credential-aware collectors and final acceptance remain #193/#194.

## Authentication and route policy

The existing Spring Security filter establishes a registered, enabled `QueryPrincipal` before
Controller selector validation. All five approved Principals may read Market: A/B/C with their
different Account Grants, Operations with operations-read only, and D with neither permission.
An Account Grant or operations-read permission is not a Market requirement. Removing an Account
Grant does not remove this independent route permission; disabling the Principal rejects it.
Anonymous, unregistered and disabled callers receive 401 with the existing Bearer challenge.

The security rule matches only the existing three-selector GET route. Unknown descendants,
incomplete paths, trailing slash and unapproved methods remain denied. HEAD/OPTIONS do not gain
access through framework-generated behavior. Direct HTTP uses the same filter policy; caller
identity headers do not establish a Principal. No new authentication mechanism is introduced.

## Selection, identity and artifact meaning

Trading day uses canonical ISO LocalDate parsing, independently of the wall clock. The shared
artifact `InstrumentRef` validates and normalizes venue/symbol, preserving its trim/uppercase
behavior and existing XTAI/ROCO and 1–12 alphanumeric symbol contract. The API does not invent a
different instrument catalogue. Valid selectors with no installed row return empty 404; malformed
selectors return empty 400 after authentication.

`QueryAuthorizedReadService.marketReference` uses the existing indexed projection lookup inside
the shared short read-only `REQUIRES_NEW` / PostgreSQL `REPEATABLE READ` transaction. Returned
trading day, venue and symbol must match the selected resource. A mismatched durable reply or
required database/transaction failure returns sanitized empty 503. The Controller has no cache
dependency. Public response fields preserve installed artifact identity, rule, price band, routing
partition and installation timestamp; no authority or current-day substitution is added.

Data and the minimal `freshness.projectionState` are from the same database snapshot. The fixture
installs the existing verified FINAL artifact through `QueryProjectionRebuildService`; it retains
its original 2026-08-11 trading day and computes identity from its actual checked bytes.
An independently checked replacement artifact changes prices and content identity for the same
day. PostgreSQL concurrency tests commit replacement/deletion and a diagnostic change while a
read is paused. The accepted read retains its old artifact and diagnostics; a subsequent read sees
the replacement or missing row. This is snapshot association, not client monotonic reads.

`NO_GAP_RECORDED` means only a nonempty recorded checkpoint set is all READY. A known gap yields
`RECOVERY_REQUIRED`; absent diagnostics yield `UNKNOWN`. Kafka checkpoint state neither verifies
artifact currency/approval nor proves source coverage, catch-up or an authoritative trading limit.
Full checkpoints remain restricted to operations-read `/api/v1/freshness`. Business responses
carry `Cache-Control: no-store`, the shared minimal v1 envelope and no raw checkpoint metadata.

## Cache and certification compatibility

The last legacy business cache-returning handler is removed. Account Summary, Order, Executions
and Market now perform zero response-cache reads/fills; old keys are retained without broad clears
on reads. Warm full/foreign envelopes and malformed JSON bytes cannot replace a durable response,
restore a missing row, leak checkpoints or hide a failed PostgreSQL dependency. This is an explicit
PostgreSQL-dependent availability contract, not a cache-only service. Rebuild retains its separate
operator boundary and existing namespace cleanup behavior.

There is no published external compatibility promise, so v1 is corrected in place without a
duplicate DTO or new API version. Legacy standalone Controller fallback tests are superseded by
actual HTTP tests. The Redis TLS/ACL adapter gate remains independent; its obsolete Controller
fallback case becomes a TLS cache-write rejection case. Chain/hostname trust, ACL namespace
isolation, credential rejection, TTL, malformed JSON eviction and bounded namespace clear remain
covered. No typed cache, namespace change or Phase 2 optimization is introduced.

The standalone Query certification no longer requires four business response keys or reports
`redisRebuilt`/`redisKeysPresent` as restoration evidence. The minimal restoration fixture without
that field must pass while all unrelated failure-isolation/replay checks remain active. No fake
cache-capability flag replaces the removed assertion. Historical #137 evidence remains historical.
Changed verifier inputs cannot reuse that old PASS as current evidence.

The standalone runner still needs #194's credential/fixture integration before a new secured
deployment acceptance run. Its focused shell contracts are not a deployed certification PASS.
There is no Query standalone node in the current aggregate PhaseGraph; this slice does not add
one, reuse an old certification result, or execute full-local deployment work.

## Requirement-to-test traceability

| Requirement | Boundary | Focused evidence |
| --- | --- | --- |
| INV-01/03/06; Q9/A4 | Existing filter and exact GET | Five Principal matrix; anonymous/unregistered/disabled 401; authentication before bad selectors; direct loopback HTTP; unknown paths and HEAD/OPTIONS denied |
| INV-10; artifact identity | Shared InstrumentRef and typed durable result | Verified artifact prices/rule/routing/content ID; canonical selectors; absent row 404; mismatched day/venue/symbol 503 |
| INV-04; A1/R2 | No response-cache dependency | Matching/foreign full legacy envelopes, invalid JSON bytes, absent row and unavailable datasource; zero GET/PUT/CLEAR |
| CON-05; OD-18/Q12 | Short repeatable-read snapshot and minimal envelope | Owned PostgreSQL replacement/deletion interleavings; UNKNOWN/gap/READY summaries; no partition/as-of/caught-up fields |
| A3 permissions separation | Immutable activated V2 policy | Disabled D denied; C retains Market after its Y Account Grant is removed; application enforcement only |
| Certification compatibility | Existing shell entry points and Redis network gate | Verdict passes without obsolete key evidence; full Query shell contracts; Redis TLS/ACL checks |

```bash
./gradlew -q :services:query-service:test :services:query-service:bootJar
./gradlew -q :services:query-service:postgresJdbcTest
./gradlew -q staticAnalysis
bash scripts/test-query-redis-security.sh
```

PostgreSQL requires the existing private test environment and database ownership marker; ordinary
tests explicitly skip its concurrency cases. The Redis runner requires a TLS-capable executable,
OpenSSL and loopback networking and uses private disposable credentials. Exact source inputs,
commands, original red/failure evidence, review, cleanup and final-head CI are recorded in #192.
No source-event replay, multi-replica revocation, inbound TLS or production readiness is inferred.

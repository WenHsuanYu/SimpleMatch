# Query Service

`query-service` is the required Phase 1 read-side boundary. It owns its PostgreSQL schema and
consumes final `matching.events` and `account.lifecycle` facts in independent Kafka consumer
groups. No critical service calls Query, and the HTTP API never scans Kafka or reads another
service's database.

The [Query security and cache specification](security-external-access-spec.md) records the
conditionally approved target architecture, with a [decision register](security-decision-register.md),
[Account semantics review](account-summary-semantics-review.md), and
[phased implementation plan](../../../docs/plans/query-security-cache-implementation.md).
The [opaque authentication slice](opaque-authentication.md) implements Operations Freshness
security; the [Account Summary slice](account-read-authorization.md) adds explicit account grants,
minimal business diagnostics and a PostgreSQL-only consistent read. The
[Order slice](order-read-authorization.md) extends the same module with trusted durable ownership
and the corresponding Account Grant. The [Executions slice](execution-read-authorization.md)
adds existing-parent authorization and whole-collection integrity. The
[Market slice](market-read-authorization.md) enables authenticated artifact reads without Account
Grants. The [Lab policy lifecycle](lab-policy-lifecycle.md) verifies controlled multi-replica
revocation, credential replacement and recovery. Production maintenance, later transport/cache
phases and the aggregate Phase 1 certification retain their independent gates.

## Current HTTP security boundary

QS1-01 enables GET /api/v1/freshness only for an active registered QueryPrincipal with explicit
operations-read. QS1-02 enables exact GET /api/v1/accounts/{accountId}/summary with an explicit
Account Grant and verified returned identity. Denied and missing accounts return the same empty
404; malformed account UUIDs return 400 after authentication. Exact GET liveness/readiness endpoints
expose minimal health; opt-in POST rebuild retains its independent operator credential.
QS1-03 enables exact GET /api/v1/orders/{orderId} after trusted Order ownership and Account Grants.
QS1-04 enables exact GET /api/v1/orders/{orderId}/executions using that authorized parent and
validates every child Order/Account identity. QS1-05 enables only the existing Market Reference GET
shape for every enabled QueryPrincipal, including Operations and no-grant D. Other management
routes and unapproved methods deny by default. Direct HTTP follows the same policy; no anonymous
compatibility mode exists. This Local Lab policy does not approve external publication.

Every HTTP-serving process requires a complete validated startup policy under
simplematch.query-service.security; it never generates credentials or reloads policy dynamically.
Provision synthetic Local Lab credentials using the private fixture CLI and supply its verifier
properties through Spring Environment. See the [configuration and test runbook](opaque-authentication.md).
Local HTTP/PostgreSQL account-read acceptance and focused deployed Lab lifecycle evidence are
separate from production transport and final Phase 1 certification.

## Read APIs

The versioned API is rooted at `/api/v1`:

- `GET /orders/{orderId}` — current order lifecycle projection.
- `GET /orders/{orderId}/executions` — deterministic execution projections.
- `GET /accounts/{accountId}/summary` — latest Account lifecycle summary.
- `GET /market-reference/{tradingDay}/{venueMic}/{symbol}` — active artifact row.
- `GET /freshness` — source partition offsets and `READY`/`GAP_DETECTED` state.

All four business reads return `data` plus minimal `freshness.projectionState` from short
PostgreSQL `REPEATABLE READ` snapshots. Account checks its grant before transaction acquisition;
Order first establishes trusted ownership from the same durable row as its payload. Executions
requires that parent before reading and validating the collection; missing/denied parents return
404, while an authorized existing parent without executions returns an empty 200. Market validates
day/venue/symbol against its installed artifact row without Account Grants. These paths
bypass response-cache reads and writes; old keys are retained. Required PostgreSQL/transaction
failures return an empty 503.
`NO_GAP_RECORDED` means only a nonempty recorded checkpoint set is all READY; it does not claim
source coverage or catch-up. Full checkpoints remain restricted to operations-read `/freshness`.

The shared v1 business envelope no longer contains raw checkpoint metadata. No business route reads
or fills Redis responses. Historical keys cannot hide a PostgreSQL failure or restore absent data.
Market's snapshot-associated Kafka diagnostics do not establish artifact currency or catch-up.
Selective cache reintroduction and performance claims require the separately approved Phase 2 gates.

## Projection and rebuild contract

`V1__create_query_read_models.sql` creates the inbox, per-source checkpoints, order, execution,
Account summary, and active market-reference tables. Each source fact claims its exact event
identity and payload hash, applies the read-model update, and advances its partition checkpoint in
one local transaction. A non-contiguous offset records `GAP_DETECTED` and is acknowledged only after
that durable state is recorded; operators reset the reconstructible state with
`QueryProjectionRebuildService.resetForReplay()` and replay both consumer groups from the approved
source offsets.

The mounted market-reference artifact is checksum- and trading-day-verified through the shared
artifact contract before `QueryProjectionRebuildService.installMarketReference` replaces the active
day. Production opts into that install after migrations; local and test profiles keep it explicit.

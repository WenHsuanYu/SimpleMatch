# Query Service

`query-service` is the required Phase 1 read-side boundary. It owns its PostgreSQL schema and
consumes final `matching.events` and `account.lifecycle` facts in independent Kafka consumer
groups. No critical service calls Query, and the HTTP API never scans Kafka or reads another
service's database.

The [Query security and cache specification](security-external-access-spec.md) records the
conditionally approved target architecture, with a [decision register](security-decision-register.md),
[Account semantics review](account-summary-semantics-review.md), and
[phased implementation plan](../../../docs/plans/query-security-cache-implementation.md).
The first [opaque authentication slice](opaque-authentication.md) implements Operations Freshness
security; full account authorization and later transport/cache phases remain independently gated.

## Current HTTP security boundary

QS1-01 enables GET /api/v1/freshness only for an active registered QueryPrincipal with explicit
operations-read. Exact GET liveness/readiness endpoints expose minimal health; opt-in POST rebuild
retains its independent operator credential. Other business/management routes and methods deny
by default until their dedicated authorization tickets land. This deliberately changes direct
HTTP behavior; no anonymous collector compatibility mode exists. The historical controller/cache
behavior below is not reachable through the denied business routes in this slice.

Every HTTP-serving process requires a complete validated startup policy under
simplematch.query-service.security; it never generates credentials or reloads policy dynamically.
Provision synthetic Local Lab credentials using the private fixture CLI and supply its verifier
properties through Spring Environment. See the [configuration and test runbook](opaque-authentication.md).
No production transport, multi-replica revocation or account-read security acceptance is claimed.

## Read APIs

The versioned API is rooted at `/api/v1`:

- `GET /orders/{orderId}` — current order lifecycle projection.
- `GET /orders/{orderId}/executions` — deterministic execution projections.
- `GET /accounts/{accountId}/summary` — latest Account lifecycle summary.
- `GET /market-reference/{tradingDay}/{venueMic}/{symbol}` — active artifact row.
- `GET /freshness` — source partition offsets and `READY`/`GAP_DETECTED` state.

Every data response carries durable checkpoint freshness metadata. The current controller
evaluates PostgreSQL reads before checking the optional 30-second Redis response cache, so a
cache hit does not avoid those database reads and can select an older cached response. Keys are
shaped as `query:v1:{order|executions|account-summary|market-reference}:...`. A cache miss uses the
already evaluated durable result and repopulates that entry. Redis connections use bounded
connect and command timeouts (`500ms` and `2s` by default), so an unavailable cache returns the
durable PostgreSQL view instead of holding a public read until the client reconnect loop expires.

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

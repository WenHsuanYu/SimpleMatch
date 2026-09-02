# Query Service

`query-service` is the required Phase 1 read-side boundary. It owns its PostgreSQL schema and
consumes final `matching.events` and `account.lifecycle` facts in independent Kafka consumer
groups. No critical service calls Query, and the HTTP API never scans Kafka or reads another
service's database.

## Read APIs

The versioned API is rooted at `/api/v1`:

- `GET /orders/{orderId}` — current order lifecycle projection.
- `GET /orders/{orderId}/executions` — deterministic execution projections.
- `GET /accounts/{accountId}/summary` — latest Account lifecycle summary.
- `GET /market-reference/{tradingDay}/{venueMic}/{symbol}` — active artifact row.
- `GET /freshness` — source partition offsets and `READY`/`GAP_DETECTED` state.

Every data response carries the durable checkpoint freshness metadata. PostgreSQL is written first;
Redis is an optional 30-second read-through cache with keys shaped as
`query:v1:{order|executions|account-summary|market-reference}:...`. A missing, expired, or invalid
Redis entry reads PostgreSQL and repopulates only that cache entry. Redis connections use bounded
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

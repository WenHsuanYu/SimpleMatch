# Eventing and CQRS

This is the canonical target specification for SimpleMatch's event-driven,
CQRS, and event-sourcing posture.

## CQRS posture

SimpleMatch uses lightweight CQRS: commands and business state transitions use
the write path, while PostgreSQL and Redis projections serve reads. It does not
require every domain to have separate databases or independently deployed read
and write models.

- The write path begins with a normalized FIX command submitted to
  `risk-service`; durable admission produces an integration event for ordered
  matching.
- `matching-engine` produces execution results.
- `persistence`, an optional `query-service`, and `marketdata-streamer` build
  read-oriented views from those events.
- Read models are rebuildable and may be eventually consistent. They do not
  decide whether an order is admitted or matched.

## Event-driven, not yet fully event-sourced

The target architecture is event-driven. Services consume events, take their
local next action, and publish later events. Consumers are designed for
at-least-once delivery and replay.

That does not by itself make the system fully event-sourced. At present, the
intended authoritative state for many aggregates remains service-owned state
tables, while outbox records carry integration events. An outbox provides
reliable publication; it is not automatically an append-only domain event
store capable of rebuilding every state transition.

## Event-authoritative aggregates

If a later decision makes an aggregate event-authoritative, it must provide an
append-only event stream with at least an aggregate identity, sequence or
version, event type, event time, and schema version. Its state tables then
become projections and snapshots become an explicit rebuild optimisation.

`Order` and `Reservation` are the likely candidates, but this is a deliberate
domain decision rather than an implicit side effect of using Kafka. A pragmatic
intermediate posture is to keep orders state-table-authoritative while making
reservation transitions auditable and replayable.

## Projection rules

A projection is a query-oriented representation calculated from events, such
as an order-status table, executions table, Redis cache, or client market-data
view. Projection consumers must tolerate duplicate delivery and rebuild from a
known checkpoint. Losing a projection is recoverable; losing the authoritative
write history is not.

Wire schemas, topic names, and compatibility policy are specified in the
contracts area. This document owns the decision boundaries that give those
contracts their meaning.

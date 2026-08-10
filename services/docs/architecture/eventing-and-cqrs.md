# Eventing and CQRS

This is the canonical target specification for SimpleMatch's event-driven, CQRS, and event-sourcing
posture.

## CQRS posture

SimpleMatch uses lightweight CQRS: commands and business state transitions use the write path, while
PostgreSQL and Redis projections serve reads. It does not require every domain to have separate
databases or independently deployed read and write models.

- The write path begins with a normalized FIX command submitted to `risk-service`; durable
  admission publishes a command to the artifact-assigned `matching.commands` partition.
- `matching-engine` replays that authoritative command journal, owns in-memory order-book state,
  and produces deterministic `matching.events`.
- `persistence`, the required Phase 1 `query-service`, and the market-data projection build
  read-oriented views from those events. `marketdata-streamer` serves the resulting market-data
  views; it does not become a second Matching consumer.
- Read models are rebuildable and may be eventually consistent. They do not decide whether an order
  is admitted or matched.

## Event-driven, not yet fully event-sourced

The target architecture is event-driven. Services consume events, take their local next action, and
publish later events. Consumers are designed for at-least-once delivery and replay.

That does not make every service fully event-sourced. Account and Risk remain service-owned
PostgreSQL state, while outbox records carry their integration events. Matching is the deliberate
exception: retained `matching.commands`, beginning with the trading session's Open Barrier, is its
authoritative recovery journal. Persistence stores permanent trades and fills but is never used to
rebuild an order book.

## Event-authoritative aggregates

If a later decision makes an aggregate event-authoritative, it must provide an append-only event
stream with at least an aggregate identity, sequence or version, event type, event time, and schema
version. Its state tables then become projections and snapshots become an explicit rebuild
optimisation.

`Order` and `Reservation` are the likely candidates, but this is a deliberate domain decision rather
than an implicit side effect of using Kafka. A pragmatic intermediate posture is to keep orders
state-table-authoritative while making reservation transitions auditable and replayable.

## Projection rules

A projection is a query-oriented representation calculated from events, such as an order-status
table, executions table, Redis cache, or client market-data view. Projection consumers must tolerate
duplicate delivery and rebuild from a known checkpoint. Losing a projection is recoverable; losing
the authoritative write history is not.

Wire schemas, topic names, and compatibility policy are specified in the contracts area. This
document owns the decision boundaries that give those contracts their meaning.

## Delivery policy boundary

Matching ingress and the Persistence, Account, and QuickFIX consumer groups are critical. They
retry in partition order, persist inbox/quarantine evidence, and cannot skip an unparseable event or
same-ID/different-payload violation. The affected component blocks until the exact record is
resolved; an invariant violation can interrupt the market and is never rerouted.

The public Market Data projection is rebuildable and non-critical. It may use delayed retry and a
dead-letter topic, and its failure does not block Persistence, Account, QuickFIX, or trading
admission. Delivery metrics expose connector lag, outbox age, consumer lag, oldest unprocessed age,
duplicates, retries, quarantine, and dead-letter counts.

Outbox cleanup is a separate operational action. It is authorized only after a durable CDC
watermark has passed the configured safety window, and the deletion boundary is narrowed by the
oldest row retained for replay or operator investigation. Without those watermarks, cleanup is
disabled.

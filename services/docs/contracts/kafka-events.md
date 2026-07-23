# Kafka Event Contracts

This is the canonical target contract for cross-service Kafka events. It
defines event meaning and compatibility, not broker deployment settings or a
service's implementation details.

## Scope and delivery model

Kafka carries asynchronous integration events after durable local admission.
The synchronous ingress path is described in [the gRPC contract](grpc-apis.md).
Producers publish through a durable outbox and the event path is
at-least-once: consumers must make repeated delivery harmless. A consumer may
record an event identifier, enforce a state transition, or rely on an
appropriate domain unique key; it must not make the same business effect twice.

`orders.commands` is a transitional compatibility topic only. New ingress
integrations use synchronous admission and begin the asynchronous path with
`orders.validated` or a rejection outcome.

## Topic catalogue

| Topic | Key and ordering boundary | Producer | Consumers | Contract purpose |
| --- | --- | --- | --- | --- |
| `orders.validated` | Stable partition for a symbol within a trading day | `risk-service` | `matching-engine` | Accepted order command, or its rejection outcome |
| `matching.executions` | `symbol` | `matching-engine` | `persistence`, market-data services, `quickfix-gateway` | Executions and order-result events |
| `marketdata.events` | `symbol` | `marketdata-publisher` | `marketdata-streamer` | Public market-data events |
| `audit.events` | `symbol` or `order_id`, selected for the audit query | Owning service | Audit consumers | Append-only audit and trace events |

An event sequence that has a business ordering requirement must use the stated
key. Consumers must not infer a total order across partitions. A routing
snapshot may choose the numeric partition for `orders.validated`, but changing
that choice must preserve the documented ordering boundary during a trading
day.

## Event identity and minimum envelope

Every event uses the protobuf metadata defined in
[`common.proto`](../../../proto/common.proto): `schema_version`, `event_id`,
`created_at_unix_ms`, and `source_service`. `event_id` identifies one emitted
event and is the preferred consumer-deduplication key when it is available.

Order commands and decisions use the messages in
[`orders.proto`](../../../proto/orders.proto). `command_id` identifies the
operation on the command/event boundary. The current synchronous and
service-local name, `request_id`, denotes the same underlying operation value;
it is not a second identity axis. `order_id` identifies the order, while FIX
business identity remains separate and is defined by the
[FIX gateway contract](fix-gateway.md).

Identifiers are wire strings to preserve protocol compatibility. Producers
currently generate UUID-backed event and command values, but consumers must
treat the identifier as opaque rather than depend on a particular textual
encoding.

## Evolution and compatibility

- A published event type and field number are durable contract surface.
- Add optional fields with a new or compatible `schema_version`; do not reuse a
  field number or change the meaning of an existing field.
- A consumer must ignore fields it does not understand and reject only an
  explicitly unsupported major semantic version.
- A breaking semantic change requires a new event type or parallel topic plus
  a documented migration window; it must not silently repurpose an existing
  field or topic.
- Producers retain the information needed for replay and consumers remain
  idempotent, so backfill and replay do not create new business effects.

Service configuration, connector deployment, and current partition counts are
platform concerns; they are intentionally not repeated here.

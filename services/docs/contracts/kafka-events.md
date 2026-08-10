# Kafka Event Contracts

This is the canonical target contract for cross-service Kafka events. It defines event meaning and
compatibility, not broker deployment settings or a service's implementation details.

## Scope and delivery model

Kafka carries asynchronous integration events after durable local admission. The synchronous ingress
path is described in [the gRPC contract](grpc-apis.md). Producers publish through a durable outbox
and the event path is at-least-once: consumers must make repeated delivery harmless. A consumer may
record an event identifier, enforce a state transition, or rely on an appropriate domain unique key;
it must not make the same business effect twice.

The former `orders.commands` QuickFIX compatibility publication is retired. New and current order
ingress uses synchronous Risk v2 admission; the authoritative asynchronous order path begins from
the Risk transactional outbox on `orders.validated`. QuickFIX maps its durable WAL record directly
to the typed v2 Risk command and does not construct a v1 `OrderCommand` for production admission.

## Topic catalogue

| Topic                 | Key and ordering boundary                            | Producer               | Consumers                                               | Contract purpose                                 |
|-----------------------|------------------------------------------------------|------------------------|---------------------------------------------------------|--------------------------------------------------|
| `orders.validated`    | Stable partition for a symbol within a trading day   | `risk-service`         | `matching-engine`                                       | Accepted order command, or its rejection outcome |
| `account.lifecycle`   | `account_id`                                         | `account-service`      | Account lifecycle and rebuildable projections           | Reservation authority outcome                    |
| `market-reference.snapshots` | `trading_day`                                  | `marketdata-publisher` | Market Reference consumers                              | Immutable market snapshot                        |
| `market-reference.routing-policies` | `trading_day`                           | `marketdata-publisher` | `risk-service`, `matching-engine`                       | Immutable instrument-to-partition policy         |
| `matching.executions` | `symbol`                                             | `matching-engine`      | `persistence`, market-data services, `quickfix-gateway` | Executions and order-result events               |
| `marketdata.events`   | `symbol`                                             | `marketdata-publisher` | `marketdata-streamer`                                   | Public market-data events                        |
| `audit.events`        | `symbol` or `order_id`, selected for the audit query | Owning service         | Audit consumers                                         | Append-only audit and trace events               |

An event sequence that has a business ordering requirement must use the stated key. Consumers must
not infer a total order across partitions. A routing snapshot may choose the numeric partition for
`orders.validated`, but changing that choice must preserve the documented ordering boundary during a
trading day.

## Current routing assertions

The Java outbox tests and native fixture tests enforce these decisions at the producer boundary:

- Accepted v2 orders use the normalized `VENUE_MIC:SYMBOL` key (for example `XTAI:2330`), carry the
  authoritative `routing_policy_id`, and persist the selected `orders.validated` partition. A
  missing accepted partition is an error; it is never encoded as partition zero.
- Any retained v1 compatibility adapter is outside the QuickFIX production admission path and does
  not restore a legacy Kafka command route.
- Account lifecycle events use `account_id` as their key. Their nullable partition column means the
  Kafka producer's key partitioner selects the partition consistently for that account.
- Market Reference snapshot and routing-policy publications use the trading day as their key.
  Routing-policy publications are pinned to the configured policy publication partition, currently
  partition `0`, while instrument assignments inside the policy select `orders.validated` routes.
- Matching executions use the instrument symbol as their ordering key. Account and QuickFIX
  consumers treat the execution stream as at-least-once and deduplicate at their own boundaries.

`V1ProtobufCompatibilityInventoryTest` and `V2ProtobufCompatibilityInventoryTest` compare every
generated descriptor field number with the checked-in inventories. `RoutingPolicyContractTest`, the
Risk admission outbox tests, Account outbox tests, Market Reference publication tests, and the
native routing-policy fixture tests are the executable routing contract for the current streams.

## Event identity and minimum envelope

Every v1 event uses the metadata in
[`common.proto`](../../../proto/common.proto). Additive v2 events and commands use
[`common_v2.proto`](../../../proto/common_v2.proto), which adds correlation and optional causation
identifiers to the stable schema version, event identity, timestamp, and source-service fields.
`event_id` identifies one emitted event and is the preferred consumer-deduplication key when it is
available.

Production synchronous order admission uses the v2 messages in
[`orders_v2.proto`](../../../proto/orders_v2.proto). `command_id` identifies one admission operation
and is reused by Gateway recovery and Risk reconciliation. `order_id` is the opaque internal order
identity at the Risk boundary, while FIX `ClOrdID`/`OrderID(37)` remain a separate external business
identity defined by the [FIX gateway contract](fix-gateway.md).

Some other streams, including the currently consumed `matching.executions` contract, remain on their
own v1 wire version until migrated independently. A v1 event in another domain does not imply that
QuickFIX-to-Risk order admission still uses v1.

Identifiers are wire strings to preserve protocol compatibility. Producers generate UUID-backed
internal event, command, order, and account identifiers where required by the v2 contract, and
consumers treat those identifiers as opaque rather than depending on a particular textual encoding.

The v2 order-admission contract uses signed 64-bit `0.0001 TWD` fixed-point price/notional values and
whole-share quantities. See [v2 domain contracts](v2-domain-contracts.md) for the typed wire values
and compatibility boundary.

## Evolution and compatibility

- A published event type and field number are durable contract surface.
- Add optional fields with a new or compatible `schema_version`; do not reuse a field number or
  change the meaning of an existing field.
- A consumer must ignore fields it does not understand and reject only an explicitly unsupported
  major semantic version.
- A breaking semantic change requires a new event type or parallel topic plus a documented migration
  window; it must not silently repurpose an existing field or topic.
- Producers retain the information needed for replay and consumers remain idempotent, so backfill
  and replay do not create new business effects.

Service configuration, connector deployment, and current partition counts are platform concerns;
they are intentionally not repeated here.

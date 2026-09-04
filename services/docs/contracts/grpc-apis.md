# gRPC API Contracts

This is the canonical target contract for synchronous cross-service calls. It defines the admission
boundary and shared call semantics; service-owned API implementations remain with the services that
own them.

## Admission API

`quickfix-gateway` synchronously submits new and cancel commands to
`risk-service`. The authoritative protobuf service is
[`risk_v2.proto`](../../../proto/risk_v2.proto), using the command types in
[`orders_v2.proto`](../../../proto/orders_v2.proto):

| RPC              | Request              | Successful response meaning                                                                          |
|------------------|----------------------|------------------------------------------------------------------------------------------------------|
| `SubmitNewOrder` | `NewOrderCommand`    | The request was accepted or rejected after the risk-service decision and durable admission boundary. |
| `SubmitCancel`   | `CancelOrderCommand` | The cancellation request was accepted or rejected at the same boundary.                              |

`command_id` is the stable operation identity supplied by the caller. A successful
`SubmitNewOrder` is the boundary after which the gateway may send the first successful client
acknowledgement. A rejected or unavailable
admission must not be represented as a successful acknowledgement.

## Trading-session close API

`quickfix-gateway` requests final trading-session closure from `risk-service` through
`TradingSessionOperationsService.CloseTradingSession` in
[`risk_v2.proto`](../../../proto/risk_v2.proto). The request carries the deterministic
`trading_session_id`; Risk owns construction and durable outbox insertion of the complete Close
Barrier set.

A successful response means Risk has accepted durable publication responsibility for the idempotent
Close Barrier set. A repeated request has the same successful response once that responsibility is
already durable. The response does not imply that Kafka publication, Matching consumption, or
downstream consumer drain has completed.

Gateway closes local admission before making this call and never reopens admission because the Risk
call failed. Risk returns `INVALID_ARGUMENT` for a malformed or mismatched trading-session identity,
`UNAVAILABLE` for a temporary persistence/transaction availability failure, and `INTERNAL` for an
unexpected server failure. Gateway may retry only the explicitly retryable transport outcomes, with
a bounded attempt count, while preserving the original trading-session identity.

## Time, retries, and failure handling

- Every outbound call sets a finite deadline. The caller budgets that deadline within its own
  request deadline rather than waiting indefinitely.
- Read-only calls may retry transient `UNAVAILABLE`, `DEADLINE_EXCEEDED`, or
  `RESOURCE_EXHAUSTED` failures with a small bounded exponential backoff and jitter.
- Calls with a business side effect do not retry automatically unless their operation identity is
  supplied and the receiving service guarantees an idempotent result for it.
- Callers isolate failing downstream dependencies with a circuit breaker and resource boundary. Risk
  and reservation decisions fail closed; a caller must not invent an approval when the dependency is
  unavailable.

## Idempotency and compatibility

The receiving service owns idempotency. Replaying the same operation identity with equivalent
content returns the same logical result and must not reserve, accept, or persist a second business
operation. Reuse of an identity with different content is a conflict and must be rejected or
surfaced explicitly.

Protobuf package and service names are versioned contract surface. Additive fields and methods are
preferred. Removed fields, changed field meanings, or a changed response outcome require a new
versioned RPC or a documented migration period; callers must not depend on unknown fields being
present.

The legacy `MarketDataService.SubscribeMarketData` stream remains the `MarketDataEvent` contract.
The complete public snapshot stream is additive as
`SubscribeMarketDataSnapshots`; it does not change the response type of the legacy RPC.

The contract does not prescribe gRPC load-balancing or Kubernetes discovery configuration. Those are
platform deployment decisions.

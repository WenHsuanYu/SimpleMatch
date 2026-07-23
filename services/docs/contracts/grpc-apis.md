# gRPC API Contracts

This is the canonical target contract for synchronous cross-service calls. It
defines the admission boundary and shared call semantics; service-owned API
implementations remain with the services that own them.

## Admission API

`quickfix-gateway` synchronously submits new and cancel commands to
`risk-service`. The authoritative protobuf service is
[`risk_service.proto`](../../../proto/risk_service.proto), using the command
types in [`orders.proto`](../../../proto/orders.proto):

| RPC | Request | Successful response meaning |
| --- | --- | --- |
| `SubmitOrder` | `OrderCommand` | The request was accepted or rejected after the risk-service decision and durable admission boundary. |
| `CancelOrder` | `OrderCommand` with cancel type and original client order identity | The cancellation request was accepted or rejected at the same boundary. |

`request_id` in each response is the synchronous name for the supplied
`command_id`; both name the same operation. A successful `SubmitOrder` is the
boundary after which the gateway may send the first successful client
acknowledgement. A rejected or unavailable admission must not be represented as
a successful acknowledgement.

## Time, retries, and failure handling

- Every outbound call sets a finite deadline. The caller budgets that deadline
  within its own request deadline rather than waiting indefinitely.
- Read-only calls may retry transient `UNAVAILABLE`, `DEADLINE_EXCEEDED`, or
  `RESOURCE_EXHAUSTED` failures with a small bounded exponential backoff and
  jitter.
- Calls with a business side effect do not retry automatically unless their
  operation identity is supplied and the receiving service guarantees an
  idempotent result for it.
- Callers isolate failing downstream dependencies with a circuit breaker and
  resource boundary. Risk and reservation decisions fail closed; a caller must
  not invent an approval when the dependency is unavailable.

## Idempotency and compatibility

The receiving service owns idempotency. Replaying the same operation identity
with equivalent content returns the same logical result and must not reserve,
accept, or persist a second business operation. Reuse of an identity with
different content is a conflict and must be rejected or surfaced explicitly.

Protobuf package and service names are versioned contract surface. Additive
fields and methods are preferred. Removed fields, changed field meanings, or a
changed response outcome require a new versioned RPC or a documented migration
period; callers must not depend on unknown fields being present.

The contract does not prescribe gRPC load-balancing or Kubernetes discovery
configuration. Those are platform deployment decisions.

# FIX Gateway Contract

This is the canonical target contract for SimpleMatch's external FIX boundary.
It specifies FIX 4.4 message semantics and the mapping to internal admission
and execution outcomes. The service-owned runtime and certification details
remain in the [quickfix-gateway README](../../quickfix-gateway/README.md).

## Boundary and command flow

`quickfix-gateway` is a FIX 4.4 Acceptor. It validates and normalizes inbound
FIX order flow, submits it to `risk-service` through the
[gRPC admission contract](grpc-apis.md), and maps execution outcomes back to
the same FIX session. It never owns matching: the C++ `matching-engine` owns
order-book matching and emits `matching.executions` under the
[Kafka event contract](kafka-events.md).

| Client intent | FIX inbound message | Internal outcome |
| --- | --- | --- |
| New order | `NewOrderSingle` (`35=D`) | Synchronous admission followed by `orders.validated` or a rejection outcome |
| Cancel request | `OrderCancelRequest` | Synchronous cancellation admission followed by an execution or cancellation outcome |
| Execution update | — | `ExecutionReport` (`35=8`) |
| Cancel rejection | — | `OrderCancelReject` (`35=9`) |

The first successful `PendingNew` acknowledgement is sent only after durable
admission succeeds. A local gateway WAL may assist recovery and audit, but it
is not the authoritative successful-admission boundary.

## Execution-report mapping

| Internal outcome | FIX response | Required semantics |
| --- | --- | --- |
| Accepted, pending admission completion | `ExecutionReport` | `ExecType=PendingNew`, `OrdStatus=PendingNew` |
| Eligible to match | `ExecutionReport` | `ExecType=New`, `OrdStatus=New` when the optional second acknowledgement is used |
| Partial or full execution | `ExecutionReport` | `PartialFill` or `Fill`, plus last, cumulative, leaves, and average quantity/price values |
| New-order rejection | `ExecutionReport` | `ExecType=Rejected`, `OrdStatus=Rejected`, with reason text/code where available |
| Successful cancel | `ExecutionReport` | `ExecType=Canceled`, `OrdStatus=Canceled`, and both cancel and original client order identifiers |
| Cancel rejection | `OrderCancelReject` | `ClOrdID`, `OrigClOrdID`, current `OrdStatus`, `CxlRejResponseTo=CancelRequest`, and reason/text |

Every outbound report has a stable `ExecID`, internal `OrderID`, and
`TransactTime`. A repeated report uses the same `ExecID`; the gateway must not
mint a second business execution merely because a FIX message is resent.

## Session resend and business deduplication

QuickFIX session behavior preserves message sequence and resend semantics,
including the protocol markers required for a resent message. Business
idempotency is separate: a new-order identity is unique at least within its
FIX session and trading day, conventionally
`(SenderCompID, TargetCompID, TradingDay, ClOrdID)`.

For a repeated `NewOrderSingle` with the same business identity, equivalent
content returns the existing logical result and differing content is rejected
as a conflict. A cancel uses a new `ClOrdID` and the target `OrigClOrdID`; it
is subject to the same no-double-effect rule. `risk-service` owns the durable
business deduplication decision.

## Compatibility

FIX 4.4 and the repository dictionary at
[`../../../config/quickfix/fix-spec/FIX44.xml`](../../../config/quickfix/fix-spec/FIX44.xml) are the baseline. A
counterparty-specific field, tag, or session rule is a versioned extension:
it must be negotiated and tested with a dedicated dictionary or configuration,
not silently change the baseline meanings above. The cross-service event and
RPC contracts must carry the data needed to preserve these FIX semantics, but
they do not expose the FIX session directly.

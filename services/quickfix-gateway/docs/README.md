# QuickFIX gateway specification

`quickfix-gateway` owns the external FIX session boundary. It normalizes FIX messages, records
inbound intent durably, and performs synchronous submission to `risk-service`; it does not own risk
decisions or matching order.

## Owned responsibilities

- Accept and manage FIX 4.4 sessions.
- Map supported inbound FIX order flow to internal commands.
- Append inbound traffic to the local write-ahead log before the first business-level
  acknowledgement.
- Return `PendingNew` only after `risk-service` has accepted durable admission.
- Map `matching.executions` back to outbound FIX responses for the owning session.

## Session ownership and interruption behavior

The configured gateway `ownerId` claims each `SessionID` at logon. Claims are idempotent for the
same owner; a conflicting owner fails closed and cannot process application messages or emit FIX
execution responses. The deployment's owner-aware endpoint and same-owner restart model are
specified in [`docs/quickfix-gateway-session-scale-plan.md`](../../../docs/quickfix-gateway-session-scale-plan.md).

The admission gate is explicit for both `NewOrderSingle` and `OrderCancelRequest`. A paused gate
returns `ADMISSION_PAUSED`; a market interruption returns `MARKET_INTERRUPTED`. The gateway sends
the corresponding FIX rejection before WAL append or Risk submission, and `reopen()` is the only
transition that resumes both durable paths.

## Source of truth

The service's [runtime README](../README.md) is the canonical implementation and operation guide.
This page is the target-specification entry point: it defines the gateway's ownership boundary
without copying the shared event, ordering, or reliability specifications.

Keep FIX-session and ingress-specific decisions with this service. Keep cross-service protocols and
architecture rules in `services/docs/`.

## Anti-corruption layer

FIX is an external protocol model, not the internal order domain. `FixOrderSnapshot` and
`FixExecutionIdentity` form the small adapter vocabulary needed by `FixMessageMapper`. Production
callers create the order snapshot from the durable
`WalRecord`, then supply one execution identity and, for rejection, one reason text. This removes
positional seven- and eight-argument calls while keeping QuickFIX classes and FIX field semantics
inside the gateway.

The gateway must not promote FIX-specific fields such as `ClOrdID`, `ExecID`, or `OrdStatus` into
shared domain types. The mapper remains responsible for the exact wire representation, which is
protected by golden-message tests.

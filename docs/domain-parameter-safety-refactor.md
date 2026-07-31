# Domain parameter-safety refactor

This document records the implementation scope and the remaining intentional wide representations
after ADR 0002. It is not a rule that every type must have five or fewer components. The review
criterion is whether positional values cross into business behavior without domain translation.

## Completed production migrations

| Previous boundary                                                                | Domain-shaped boundary                                                                                 | Compile-time protection                                                            |
|----------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| `ReserveOperation(String, String, String, String, Side, BigDecimal, BigDecimal)` | `ReserveOperation(ReservationRequestIdentity, ReservationTerms)`                                       | request/order/account IDs and quantity/price have distinct types                   |
| `release(String, String, String, String)`                                        | `release(ReleaseReservationOperation)`                                                                 | request/reservation/order IDs cannot be exchanged                                  |
| `applyFill(... seven values ...)`                                                | `applyFill(ApplyFillOperation)`                                                                        | reservation identity, execution ID, sequence, quantity, and price are named values |
| flat 15-field `AdmissionCommand` construction                                    | `AdmissionCommand(AdmissionIdentity, AdmissionOrder, AdmissionFixIdentity, AdmissionRoutingReference)` | command/order/account UUIDs and FIX identities are distinct types                  |
| flat 12–15-field `SubmissionResult` construction                                 | composed `SubmissionResult` domain values                                                              | accepted/rejected outcome and storage-safe identity own their invariants           |
| `buildPendingNew` / `buildRejected` positional FIX values                        | `FixOrderSnapshot` plus `FixExecutionIdentity`                                                         | order ID, ClOrdID, symbol, quantity, and ExecID cannot be exchanged                |
| eight-value v2-to-v1 helper                                                      | adapter receives the source protobuf command                                                           | compatibility mapping is explicit and source-oriented                              |

Deprecated positional overloads remain only as compatibility adapters. Repository production call
sites use the typed APIs; removal waits for all external fixtures and neighboring callers to
migrate.

## Intentional wide representations

| Type                                                    | Why it remains wide                                                        | Required containment                                                                      |
|---------------------------------------------------------|----------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| `AdmissionJournalEntry`                                 | immutable mapping of one SQL journal row and optimistic-version state      | JDBC flattens/rehydrates it; business entry points receive `AdmissionCommand`             |
| `WalRecord`                                             | durable gateway representation of an inbound FIX message and audit payload | QuickFIX adapter maps it to `FixOrderSnapshot` before rendering behavior                  |
| `AccountReservation`, `AccountLimit`, `AccountPosition` | authoritative persistence snapshots with version and timestamps            | lifecycle mutation remains in the transaction-owning application service                  |
| `ReservationRecord`, `AdmissionResult`                  | legacy boundary/result snapshots                                           | no new business behavior is added to these carriers                                       |
| market snapshot records                                 | coherent immutable published/imported snapshot data                        | validation occurs in the market-reference context; consumers receive a versioned snapshot |
| `PlatformProperties`                                    | top-level composition of already-grouped configuration capabilities        | nested property groups, not primitive positional arguments, define the public structure   |

## Review checklist

A new method or record with more than five values must answer these questions in review:

1. Is the type a wire, SQL, WAL, configuration, or event representation whose external shape must be
   preserved?
2. Do multiple values form a stable use-case command or value object in the owning bounded context?
3. Can equal Java types be exchanged without a compiler error?
4. Do the values share one lifecycle and invariant, or are multiple states being flattened together?
5. Does a proposed wrapper add ubiquitous language and validation, or merely hide the parameter
   count?
6. Is the transaction owner still explicit after the change?
7. Are compatibility overloads deprecated, delegated, tested, and scheduled for removal?

The accepted solution is the smallest model that answers the business problem. Builders and generic
parameter bags are not accepted as the sole repair because they do not create type safety or domain
meaning.

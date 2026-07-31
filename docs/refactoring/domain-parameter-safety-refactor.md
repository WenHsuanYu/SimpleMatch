# Domain parameter-safety refactor

This document records the implementation scope after ADR 0002. Every handwritten production Java
constructor and method with more than seven parameters must be replaced with a shorter semantic
interface. This includes records, configuration, persistence, WAL, and event representations. An
external shape may remain wide only at its adapter; generated sources are excluded, and tests use the
same semantic construction vocabulary.

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

No positional overload with more than seven parameters remains as a compatibility adapter. Migrate
all in-repository production callers, fixtures, and neighboring callers, then remove the member while
preserving its external SQL, protobuf, FIX, WAL, Kafka, or configuration contract through adapters.

## Wide external shapes under migration

| Slice | External shape | Required semantic representation and adapter containment |
|---|---|---|
| 1. Durable submission outcomes | submission journal row and result payload | `SubmissionReference`, FIX identities, persisted identity, and outcome remain the only Java construction vocabulary; adapters flatten them. |
| 2. Account Authority lifecycle state | reservation, limit, position, and legacy result rows | identity, terms, quantities, outcome, and audit/version groups compose the Java model; the transaction-owning application module remains the seam. |
| 3. Risk Admission journal state | admission journal row and result payload | identity, order facts, FIX identity, routing, decision, and audit groups compose the Java model; JDBC flattens and rehydrates them. |
| 4. QuickFIX ingress and WAL state | raw FIX message, WAL row, and session correlation | the adapter contains protocol fields; durable intent is composed from session/command identity, order terms, and audit groups. |
| 5. QuickFIX configuration and runtime policy | configuration namespace and runtime values | capability and resilience policy groups compose the Java model; configuration binding maps the unchanged namespace. |

## Slice 1: durable submission outcomes

`SubmissionResult` is the first implementation slice. Its public Java interface remains the five
semantic values already represented by the canonical constructor:
`SubmissionReference`, `FixSubmissionIdentity`, `PersistedFixIdentity`, `SubmissionOutcome`, and the
creation timestamp.

`SubmissionDecisionFactory` remains responsible for identifier normalization, surrogate identity, and
accepted/rejected construction. `SubmissionResult` remains responsible for complete value ownership
and its local invariants. `JdbcSubmissionRepository` remains an adapter: it flattens semantic values
to the existing SQL row and rehydrates them from that row; it does not decide business outcomes.

Tests migrate to a test-only semantic fixture factory with complete named scenarios. The factory does
not provide a generic builder or arbitrary primitive overrides. Verification covers accepted and
rejected JDBC round-trips, persisted FIX identity and surrogate state, unchanged outbox payload, and
unchanged schema. Completion requires removal of all 12-, 14-, and 15-parameter constructors and
their PMD suppressions.

## Slice 2: Account Authority lifecycle state

`AccountReservation` composes stable reservation identity, account ownership, immutable terms, and
`ReservationLifecycle`. The lifecycle owns remaining and filled quantity, held authority, outcome,
and revision history because reserve, partial fill, release, and rejection change or validate those
facts as one state machine. `AccountLimit` and `AccountPosition` remain separate aggregate values:
their identity, ledger or inventory, and revision groups are not shared as a generic account-state
carrier.

`ReservationRecord` becomes a semantic response projection of the authoritative reservation; it is
not a second persistence model. Remove `IdempotentReservationService` and
`JdbcReservationRepository`, which otherwise write a weaker direct path to the same reservation
table without coordinating account limit, position, and outbox work. `AccountLifecycleOutbox`
remains infrastructure composed from event identity, destination, serialized payload, aggregate
reference, and creation time. Its JDBC adapter is the only row mapping.

Completion requires semantic constructors for reservation, limit, position, response, and outbox;
removal of the direct legacy writer and its tests; reserve/partial-fill/release/rejection/replay
transaction tests; outbox payload compatibility tests; and unchanged account SQL schema.

## Slice 3: Risk Admission journal state

`AdmissionJournalEntry` composes `AdmissionCommand`, `AdmissionDeliveryRoute`, and
`AdmissionLifecycle`. The lifecycle has state-specific decisions: pending has no decision, accepted
new orders have a reservation reference, accepted cancellations explicitly require none, and
rejections have a stable code and detail. Its revision owns version and timestamps.

At begin admission, risk-service resolves the partition for the command symbol using the existing
configured routing policy, persists the value with the pending journal entry, and later publishes to
`orders.validated` using the symbol as message key and the recorded explicit partition. Recovery
uses the recorded partition rather than recomputing it. `AdmissionResult` is a separate projection
of admission identity, decision, opaque routing-policy provenance, and delivery route.

The existing optional ingress `routingSnapshotId` is not the local routing JSON version and remains
opaque. Moving symbol-to-partition assignment into Market Reference is a deferred cross-service
change; it needs its own versioned contract, schema migration, and consumer rollout. Completion
requires semantic journal/result constructors, journal and recovery route round-trips, symbol-keyed
explicit-partition outbox tests, and unchanged SQL/protobuf shapes.

## Review checklist

A new handwritten production Java method or constructor with more than seven parameters must be
refactored before merge. Review must answer these questions:

1. Which external shape, if any, must its adapter preserve?
2. Do multiple values form a stable use-case command or value object in the owning bounded context?
3. Can equal Java types be exchanged without a compiler error?
4. Do the values share one lifecycle and invariant, or are multiple states being flattened together?
5. Does a proposed wrapper add ubiquitous language and validation, or merely hide the parameter
   count?
6. Is the transaction owner still explicit after the change?
7. Have all in-repository callers migrated so the positional Java member can be removed?

The accepted solution is the smallest deep module that answers the business problem. Builders and
generic parameter bags are not accepted as the sole repair because they do not create type safety,
domain meaning, leverage, or locality.

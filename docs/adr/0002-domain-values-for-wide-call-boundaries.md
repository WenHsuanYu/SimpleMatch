# ADR 0002: Domain values for parameter-safe call boundaries

Status: accepted.

## Context

The blocking PMD `ExcessiveParameterList` rule exposed several boundaries where multiple `String`,
`UUID`,
`BigDecimal`, timestamp, and status values were passed positionally. The highest-risk examples were
account reserve, release, and fill application; durable risk admission and submission outcomes; FIX
execution-report mapping; and v2-to-v1 command adaptation. Those APIs required the caller to
remember a positional contract, and the compiler could not detect exchanges such as request ID
versus order ID, sender versus target, quantity versus price, or reason code versus reason detail.

SimpleMatch already separates service-owned authoritative state from transport and persistence
concerns. The solution must therefore improve the model without creating a shared enterprise domain
model or moving Spring, QuickFIX/J, protobuf, JDBC, or Kafka types into core business concepts. It
also must not move transaction ownership out of the application services or alter existing wire,
schema, idempotency, and event contracts.

The previous policy allowed a handwritten Java persistence snapshot, protocol envelope,
configuration record, or deprecated compatibility overload to remain wide when it mirrored an
external shape. That exception kept positional Java interfaces in the model and made the threshold
optional. The repository now requires every handwritten production Java constructor and method with
more than seven parameters to use a shorter semantic interface, including record canonical
constructors and configuration, persistence, WAL, and event representations. Generated sources are
outside this rule; tests must use the same semantic construction vocabulary as production code.

## Decision

Use a deliberately small tactical DDD model at boundaries that have stable language and invariants.
The design uses three complementary techniques:

1. **Value objects** give same-shaped values distinct Java types and validate context-free
   invariants at creation.
2. **Application commands** group the values required by one use case instead of grouping fields
   merely to reduce a numeric parameter count.
3. **Anti-corruption-layer values** translate external FIX and protobuf representations before
   business behavior is invoked.
4. **Semantic representation groups** compose handwritten persistence, WAL, event, and
   configuration models. Their adapters alone flatten and rehydrate the unchanged external shape.

The implemented model is:

- `ReservationRequestIdentity`, `ReservationTerms`, and `ReserveOperation` express “reserve
  authority for this order”.
- `ReservationIdentity`, `ReleaseReservationOperation`, `ExecutionFill`, and `ApplyFillOperation`
  express reservation lifecycle commands. Separate `FillQuantity` and `FillPrice` types make
  positional exchange a compile-time error.
- `AdmissionCommand` is composed from `Identity`, `Order`, `FixIdentity`, and `RoutingReference`.
  Its typed UUID and FIX identifiers prevent command/order/account and sender/target/client-order
  exchanges.
- `SubmissionReference`, `FixSubmissionIdentity`, `PersistedFixIdentity`, `SubmissionOutcome`, and
  `SubmissionRejection` compose `SubmissionResult`; each value owns one invariant and one change
  reason.
- `AdmissionFailure` is the transport-independent reason an order cannot enter durable admission.
  Named factories expose the current risk validation vocabulary.
- `FixOrderSnapshot` and `FixExecutionIdentity` are gateway-local anti-corruption-layer values. They
  improve QuickFIX mapping without pretending that FIX fields are the shared order domain.
- `V1OrderCommandAdapter` receives the source protobuf command directly. Version translation remains
  explicit at the compatibility boundary instead of flattening the command into an eight-argument
  helper.

Public positional constructors and methods with more than seven parameters are not retained as
compatibility adapters. Migrate every in-repository caller to the semantic interface, then remove
the positional Java member. This is an intentional Java source and binary compatibility break; SQL,
protobuf, FIX, WAL, Kafka, and configuration contracts remain compatible through their adapters.
No suppression or deprecated overload is an exception to the parameter limit.

## SubmissionResult slice

The first migration slice deepens the durable submission outcome module. `SubmissionResult` owns the
complete semantic result assembled from `SubmissionReference`, `FixSubmissionIdentity`,
`PersistedFixIdentity`, `SubmissionOutcome`, and its creation timestamp. It does not normalize
identifiers or decide acceptance; `SubmissionDecisionFactory` owns those decisions. The JDBC adapter
only flattens these values into the existing row and rehydrates them when reading it.

Test fixtures use a test-only semantic factory with complete named scenarios such as accepted and
rejected outcomes. The factory does not expose a generic builder, a parameter bag, or arbitrary
primitive overrides that could create invalid combinations. Accepted outcomes have no rejection;
rejected outcomes require stable nonblank code and detail; persisted FIX identity and its surrogate
flag are inseparable.

The slice is complete only when the flat constructors and their suppressions are removed, all
production callers, fixtures, and tests use semantic construction, accepted and rejected JDBC
round-trips pass, persisted FIX identity and surrogate state round-trip, and the outbox payload and
database schema remain unchanged.

## Account Authority lifecycle slice

Account Authority keeps Reservation, Account limit, and Account position as separate aggregate
roots. `AccountReservation` composes its stable identity, account ownership, immutable reservation
terms, and a `ReservationLifecycle`. That lifecycle owns the fields that change together: remaining
and filled quantity, held authority, outcome, and revision history. It rejects state combinations
that cannot occur in the lifecycle: rejected reservations hold no authority and have a stable
reason; released reservations have no remaining authority; and applied reservations are fully
filled. Release ends unused authority only; it neither reverses a fill nor cancels the matching
order.

`AccountLimit` and `AccountPosition` retain separate identity, state, and revision values. A daily
notional ledger is not a symbol-level inventory even though both use optimistic versions. The
transaction-owning `AccountReservationApplicationService` is the only supported reservation write
path. The older direct-row reservation path is removed rather than refactored into a second,
weaker lifecycle.

`AccountLifecycleOutbox` remains account-service infrastructure. It composes event identity,
destination, serialized payload, aggregate reference, and creation time; its JDBC adapter alone
flattens those values. The slice is complete only when the reservation, limit, position, legacy
response, and outbox Java interfaces are semantic; the legacy direct writer is gone; account
transaction tests cover reserve, partial fill, release, rejection, and duplicate replay; and the
existing SQL and account lifecycle event shapes remain compatible.

## Risk Admission journal slice

`AdmissionJournalEntry` composes a validated `AdmissionCommand`, an `AdmissionDeliveryRoute`, and
an `AdmissionLifecycle`. The command retains order facts, FIX business identity, and the opaque
routing-policy reference supplied at ingress. The delivery route owns the partition resolved for
the validated-order topic. The lifecycle uses state-specific decisions rather than a state enum plus
unrelated nullable fields: pending has no decision, accepted new orders have a reservation
reference, accepted cancellations explicitly require none, and rejection has a stable nonblank code
and detail. Revision and timestamps belong to that lifecycle.

`AdmissionResult` is a separate projection of Admission identity, decision, routing-policy
provenance, and delivery route; it does not copy journal revision history or full order facts. The
JDBC adapter alone flattens and rehydrates the journal row. When an admission begins, risk-service
uses its configured symbol-to-partition policy, records the resolved partition with the pending
journal entry, and publishes with the symbol as message key and that explicit outbox partition.
Finalization and pending recovery reuse the recorded route rather than re-resolving it.

The ingress `routingSnapshotId` remains optional and opaque in this slice. Its UUID cannot be
treated as the version of the current local routing JSON, whose identifier is a separate string and
is not exposed by the resolver. Persisting the resolved partition preserves retry consistency;
making routing policy a versioned Market Reference contract is deferred work. The slice is complete
only when journal and result positional constructors are removed, pending/accepted/rejected JDBC
round-trips and recovery retain the exact partition, accepted outbox records use the symbol key and
explicit partition, and the SQL and protobuf field shapes remain compatible.

## Domain invariants and ownership

| Term                         | Owner            | Invariant                                                                                        |
|------------------------------|------------------|--------------------------------------------------------------------------------------------------|
| Reservation request identity | account-service  | request, order, and account identifiers are present and cannot be exchanged by type              |
| Reservation terms            | account-service  | symbol and side are present; quantity is positive; limit price is absent or positive             |
| Reservation identity         | account-service  | request, reservation, and order identifiers are present and refer to one locked reservation      |
| Execution fill               | account-service  | execution ID is present; sequence is non-negative when supplied; quantity and price are positive |
| Admission command            | risk-service     | validated identity, order facts, FIX identity, trading day, and routing reference are complete   |
| Submission reference         | risk-service     | request/order reference and normalized command type travel together                              |
| FIX submission identity      | risk-service     | sender, target, trading day, and client-order identifiers form one business identity             |
| Persisted FIX identity       | risk-service     | storage-safe identifiers and the surrogate flag cannot be separated                              |
| Submission outcome           | risk-service     | acceptance has no rejection; rejection always has a stable code and detail                       |
| Admission failure            | risk-service     | validation failure has a stable code and nonblank detail                                         |
| FIX order snapshot           | quickfix-gateway | execution-report mapping receives one coherent order view, not positional wire fields            |

## Boundary and transaction rules

Domain records do not perform persistence, networking, logging, or Spring work. gRPC and FIX
adapters parse and map wire values. JDBC adapters flatten and rehydrate persistence rows.
Application services continue to own transactions and state-dependent checks after loading or
locking authoritative state. Context-free validation such as nonblank IDs, positive quantity, and
positive price occurs before transaction work.

An SQL row, protobuf message, WAL record, event envelope, or configuration namespace may remain
wide only as an external shape. A handwritten Java representation must instead compose semantic
groups, and its adapter is the sole place that flattens or rehydrates that shape. No business,
persistence, protocol, event, or configuration Java interface may exceed seven parameters.

## Rejected alternatives

- **Generic parameter bags:** names such as `Parameters`, `Arguments`, `Context`, or `Dependencies`
  hide the same coupling without adding language or invariants.
- **Builder-only repair:** a builder improves visual labeling but does not make equal-typed fields
  unexchangeable and can permit partially initialized invalid states.
- **One shared order model for every service:** this would couple bounded contexts and allow FIX,
  persistence, and matching concerns to leak across service ownership.
- **Wide-carrier exception:** mirroring an external shape is not a reason to retain a handwritten
  Java interface with more than seven parameters.
- **Mechanical wrapping:** a wrapper that does not express a semantic group, lifecycle, or invariant
  merely hides a wide interface and is rejected.

## Consequences

Call sites express intent through domain terms, and the compiler rejects the most dangerous
positional mistakes. Tests create one complete semantic value and vary only the fact under
examination. Persistence and wire compatibility remain intact because adapters preserve the external
shape and schemas are unchanged. The cost is a larger number of small types and explicit adapter
mapping; this is accepted because every field group must have stable meaning, a shared lifecycle, or
an invariant.

The root [`CONTEXT.md`](../../CONTEXT.md) is the canonical context map and ubiquitous-language
reference. Service READMEs own service-local terminology, while cross-service contracts remain under
`services/docs/contracts/`.

## Verification and migration

Required verification includes value-object invariant tests, account transaction integration tests
for duplicate and oversized fills, risk validator tests for accepted and rejected outcomes, FIX
golden-message tests, v1/v2 adapter tests, and `./gradlew staticAnalysis`. The refactoring must not
change SQL schema, protobuf schema, event payload, transaction boundary, idempotency key, or FIX
field output.

Migration proceeds in five independently verifiable slices: durable submission outcomes, Account
Authority lifecycle state, Risk Admission journal state, QuickFIX ingress and WAL state, then
QuickFIX configuration and runtime policy. In each slice, convert production callers, tests, and
supporting tools before removing every positional Java member with more than seven parameters.
Repository search and the relevant integration and compatibility tests must prove that the external
contract is unchanged before the slice closes.

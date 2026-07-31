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

## Decision

Use a deliberately small tactical DDD model at boundaries that have stable language and invariants.
The design uses three complementary techniques:

1. **Value objects** give same-shaped values distinct Java types and validate context-free
   invariants at creation.
2. **Application commands** group the values required by one use case instead of grouping fields
   merely to reduce a numeric parameter count.
3. **Anti-corruption-layer values** translate external FIX and protobuf representations before
   business behavior is invoked.

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

Public positional constructors and methods that could already be used by tests or neighboring
modules remain as
`@Deprecated(forRemoval = false)` compatibility adapters and immediately delegate to the typed API.
New production callers use only the typed API. These overloads may suppress
`PMD.ExcessiveParameterList` only while an explicit removal issue exists.

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

A flat SQL row, protobuf message, WAL record, or event envelope may remain wide when its shape
mirrors an external contract. Such a representation must not become the business method signature.
The adapter translates it to domain values before invoking behavior.

## Rejected alternatives

- **Generic parameter bags:** names such as `Parameters`, `Arguments`, `Context`, or `Dependencies`
  hide the same coupling without adding language or invariants.
- **Builder-only repair:** a builder improves visual labeling but does not make equal-typed fields
  unexchangeable and can permit partially initialized invalid states.
- **One shared order model for every service:** this would couple bounded contexts and allow FIX,
  persistence, and matching concerns to leak across service ownership.
- **Mechanical extraction of every wide record:** persistence snapshots and protocol envelopes have
  different reasons to be wide. Refactoring them without a business concept would add indirection
  and migration risk.

## Consequences

Call sites express intent through domain terms, and the compiler rejects the most dangerous
positional mistakes. Tests can create one complete command and vary only the domain value under
examination. Persistence and wire compatibility remain intact because legacy overloads delegate and
schemas are unchanged. The cost is a larger number of small types and explicit adapter mapping; this
is accepted only for values with stable meaning or realistic substitution risk.

The root [`CONTEXT.md`](../../CONTEXT.md) is the canonical context map and ubiquitous-language
reference. Service READMEs own service-local terminology, while cross-service contracts remain under
`services/docs/contracts/`.

## Verification and migration

Required verification includes value-object invariant tests, account transaction integration tests
for duplicate and oversized fills, risk validator tests for accepted and rejected outcomes, FIX
golden-message tests, v1/v2 adapter tests, and `./gradlew staticAnalysis`. The refactoring must not
change SQL schema, protobuf schema, event payload, transaction boundary, idempotency key, or FIX
field output.

Migration proceeds by converting production call sites first, then test fixtures and supporting
tools. Deprecated positional overloads are removed only after repository search proves no callers
remain and the relevant integration and compatibility tests pass.

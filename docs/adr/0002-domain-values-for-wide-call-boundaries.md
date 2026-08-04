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
optional. The repository now uses PMD's existing `ExcessiveParameterList` rule as the sole
automated parameter-count gate, with its default threshold of ten. Independently, handwritten
production Java interfaces targeted by parameter-safety work should have no more than six
parameters. A seven-to-ten-parameter member is acceptable only when semantic composition or module
deepening would create an artificial wrapper, and the review must record that reason. This is a
design-review exception, not a wide-carrier exception or an authorization to suppress or weaken the
PMD rule. Generated sources are outside this rule; tests must use the same semantic construction
vocabulary as production code.

The remaining parameter-safety refactoring changes only handwritten Java interfaces. Market
snapshot source JSON fields, PostgreSQL schemas and column semantics, Spring configuration keys and
defaults, FIX messages, WAL v1 JSON, protobuf contracts, and Kafka payloads remain unchanged.
Adapters and codecs flatten and rehydrate the semantic Java values. Any change to one of those
external shapes requires a separate compatibility decision and, where applicable, a versioned
migration.

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

The completed slices removed public positional constructors and methods with more than seven
parameters from their compatibility adapters. For future work, migrate every in-repository caller
to the semantic interface before removing the positional Java member. This is an intentional Java
source and binary compatibility break; SQL, protobuf, FIX, WAL, Kafka, and configuration contracts
remain compatible through their adapters. PMD is the only automated parameter-count gate; a
deprecated overload is never a semantic-boundary exception.

## Market Reference publication representation

`PublishedMarketSnapshot` remains a durable publication representation rather than a domain
aggregate. It composes snapshot identity, source provenance, canonical content, and publication
state. Canonical content remains a scalar because a one-field payload wrapper would be artificial.
The JDBC repository alone flattens and rehydrates the unchanged snapshot row.

The market snapshot source remains flat JSON. A source codec owns that external shape and
rehydrates a semantically grouped `SourceInstrument` before normalization produces a
`MarketInstrument`. JSON field names and nesting do not dictate the handwritten Java interface.

`MarketInstrument` composes instrument identity, trading rules, and an eligibility reason. Trading
rules compose board lot, tick table, and a reference-price band, which owns the lower-reference-upper
bracketing invariant. A well-formed source instrument from an unsupported venue or security type
remains in the snapshot with an explicit ineligibility reason; it is not filtered out or treated as
malformed. Instrument identity therefore retains a normalized venue value without requiring that
the venue be supported for trading.

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

## Risk Admission application modules

`OrderAdmissionApplicationService` owns synchronous new-order and cancel orchestration: validation,
backpressure, and account reservation outside a database transaction. It delegates pending and
terminal local work to `AdmissionLifecycleTransactions`, an application module that owns the
transaction template, journal, outbox, event factory, and clock. Beginning an admission is one local
transaction; final journal state and its outbox record are another atomic local transaction.

`PendingAdmissionRecovery` owns scheduled recovery. It retains the bounded age and batch, performs
remote reservation work outside a transaction, delegates terminal persistence to
`AdmissionLifecycleTransactions`, and leaves failures eligible for a later retry. Aggregate state
transitions remain in `AdmissionJournalEntry`; repositories remain thin adapters.

## Configuration capability slices

QuickFIX Gateway configuration is split into independently injectable file settings, runtime
identity and capability settings, and risk-client resilience settings. Shared platform configuration
is split into environment, Kafka, PostgreSQL, Redis, gRPC, routing, observability, and market
property modules. Consumers receive only the capabilities they use, and validation follows the same
capability seams.

Existing Spring property keys and defaults remain unchanged; multiple property records may bind
different subsets of an existing prefix. The former `QuickFixGatewayProperties` migration facade
was removed by Issue #67. `PlatformProperties` may remain only as migration scaffolding until its
final consumers migrate; it is not replaced by another arbitrary root configuration group.

Migration first completes the gateway-local QuickFIX split. Shared property modules are then
introduced and consumers migrate one service at a time with their context and configuration tests.
The shared `PlatformProperties` facade is removed only after the final consumer has migrated.

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
wide only as an external shape. A handwritten Java representation should instead compose semantic
groups, and its adapter is the sole place that flattens or rehydrates that shape. PMD is the only
automated parameter-count enforcement; Checkstyle does not set a parameter limit.

## WAL persistence slice

`WalRecord` is a FIX Gateway persistence representation rather than an aggregate. Its Java model
may compose semantic representation groups, but its codec must continue to read existing `v1`
line-delimited JSON and write the same flat `v1` field names and enum values. This protects WAL
replay after an application upgrade. Any persisted-shape change requires a new schema version and
an explicit migration or multi-version-read decision; grouping Java fields alone is not a version
change. Because no historical WAL data needs permissive recovery, the codec validates every
decoded record as strictly as a newly appended record, including the permitted FIX-message-type and
command-type pairs.
The strict reader also rejects unknown or duplicate fields, trailing JSON tokens, and malformed or
unmappable character sequences before semantic rehydration. v1 WAL bytes are UTF-8; it writes
LF-delimited records and accepts LF, CRLF, or CR while reporting physical line numbers consistently
across platforms.

The canonical Java representation is `WalRecord(WalMetadata, FixSessionIdentity,
WalOrderReference, WalCommand, RawFixMessage)`. `WalCommand` retains the FIX message type and
command type; its new-order variant composes `WalOrderTerms`. These gateway-local values express
persistence roles without becoming a shared order-domain model.

The WAL accepts only gateway-locally complete normalized commands: durable identity, raw FIX,
message-type/command-type consistency, and command-specific required fields must be valid before
append. It does not duplicate Risk Admission's account-authority, market-eligibility, trading-day,
routing, idempotency, or reservation decisions.

FIX ingress constructs the semantic command before calling `WalAppender`. Missing or oversized FIX
identities, missing required fields, and invalid normalized command values become explicit protocol
rejections before WAL append and before Risk Admission submission. The rejection renderer uses only
the wire values that are available, so an incomplete message is never forced into a valid order
snapshot. This is gateway-local completeness and basic value validation; account authority, market
eligibility, trading-day, routing, idempotency, and reservation policy remain Risk Admission rules.

`InboundFixMessageHandler` is only the public message-type dispatcher and depends on the new-order
and cancel handlers. Those handlers remain separate because their required data and failure behavior
differ. `NewOrderFixMessageHandler` owns validation, WAL append, risk submission, session
registration and response, and compatibility publication in that order. Its
`NewOrderCommandPreparer`, `NewOrderDurableAdmission`, `AcceptedNewOrderResponder`, and
`NewOrderRejectionResponder` collaborators expose those behaviors as deep modules rather than
appearing in a generic dependency or context bag. Spring composes these concrete modules in
`QuickFixGatewayIngressConfiguration`; the public inbound dispatcher receives only the two path
handlers.

`OrderSessionState` remains a gateway-local correlation snapshot rather than an aggregate. It
composes the owning FIX session, account identity used for cancellation fallback, the existing
`FixOrderSnapshot`, and `OrderSessionLifecycle`. The lifecycle continues to own current order status
and outstanding cancel correlation. Eviction and distributed session ownership are outside this
parameter-safety slice.

`WalCommand` is command-specific: a new-order command requires `WalOrderTerms`; a cancellation
requires its cancellation identity and original client-order identity but has no order terms. The
codec writes the existing blank or unspecified `v1` fields for a cancellation, rather than making
those placeholders part of the Java model.

Replay is fail-fast. If a nonblank WAL line cannot be decoded or violates these invariants, replay
stops with its line number and leaves the WAL bytes unchanged for operator investigation. The
gateway never skips, silently repairs, or continues past a durable inbound command.

`WalRecordJsonCodec` is a package-private FIX Gateway adapter. It alone flattens and rehydrates
the `v1` JSON shape and applies WAL invariants. `WalAppender` owns line-oriented file I/O,
serialization durability, and synchronization; it does not know JSON field names. The codec does
not know files, channels, locking, or replay orchestration.

The public nineteen-argument `WalRecord` construction is removed in the same slice. Every
in-repository factory, test, and caller migrates to semantic construction; no deprecated positional
constructor or compatibility overload remains. JSON compatibility belongs solely to the codec.

## Rejected alternatives

- **Generic parameter bags:** names such as `Parameters`, `Arguments`, `Context`, or `Dependencies`
  hide the same coupling without adding language or invariants.
- **Builder-only repair:** a builder improves visual labeling but does not make equal-typed fields
  unexchangeable and can permit partially initialized invalid states.
- **One shared order model for every service:** this would couple bounded contexts and allow FIX,
  persistence, and matching concerns to leak across service ownership.
- **Wide-carrier exception:** mirroring an external shape is not a reason to retain a handwritten
  Java interface that fails to express a semantic boundary.
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
supporting tools before removing positional Java members that do not express a semantic boundary.
Repository search, PMD, and the relevant integration and compatibility tests must prove that the
external contract is unchanged before the slice closes.

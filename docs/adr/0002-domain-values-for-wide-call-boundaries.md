# ADR 0002: Value objects and typed application interfaces

Status: accepted.

This ADR records the design decision and its migration history. The
[parameter-safety development reference](../development/domain-parameter-safety-refactor.md)
owns the current review policy and historical verification records. Later compatibility decisions,
including [ADR-0007](0007-retire-pre-release-compatibility.md), take precedence over the original
external-format preservation requirements.

## Context

PMD's blocking `ExcessiveParameterList` rule exposed interfaces with positional `String`, `UUID`,
`BigDecimal`, timestamp, and status parameters. The highest-risk interfaces included:

- Account reserve, release, and fill application.
- Durable risk admission and submission outcomes.
- FIX execution-report mapping.
- The now-retired v2-to-v1 command adapter.

Callers had to remember each positional contract. The compiler could not detect these exchanges:

- Request ID and order ID.
- Sender and target.
- Quantity and price.
- Reason code and reason detail.

SimpleMatch separates authoritative state in each service from transport and persistence concerns.
The refactoring must preserve four conditions:

- Do not create a shared enterprise domain model.
- Keep Spring, QuickFIX/J, protobuf, JDBC, and Kafka types outside core business concepts.
- Keep transaction ownership in application services.
- Preserve existing wire, schema, idempotency, and event contracts.

The previous policy allowed some handwritten Java models to remain wide when they mirrored an
external shape. These models included persistence snapshots, protocol envelopes, configuration
records, and deprecated compatibility overloads. That exception kept positional Java interfaces
and made the threshold optional.

PMD's existing `ExcessiveParameterList` rule remains the only automated parameter-count gate.
Its default minimum is ten. A member with ten or more parameters violates the rule. Design review
targets six or fewer parameters for handwritten production Java interfaces.

Members with seven to nine parameters require a recorded review. The review must explain why a
parameter object or simpler module interface would create an artificial abstraction. No review
authorizes ten parameters, PMD suppression, or a weaker rule. Generated sources remain outside the
rule. Tests use the same construction vocabulary as production code.

Parameter-safety refactoring changes handwritten Java interfaces. These external contracts remain
unchanged:

- Market snapshot source JSON fields.
- PostgreSQL schemas and column semantics.
- Spring configuration keys and defaults.
- FIX messages.
- Persisted WAL JSON.
- Protobuf contracts.
- Kafka payloads.

Adapters and codecs flatten and rehydrate the semantic Java values. Changing an external shape
requires a separate compatibility decision. It also requires a versioned migration where
applicable.

## Decision

Use tactical domain-driven design (DDD) where the owning domain has stable language and invariants.
Use adapters and data models for transport, persistence, and configuration concerns. The design
uses four complementary techniques:

1. **Value objects** give values of the same shape distinct Java types. They validate context-free
   invariants at creation. A Java record is a value object only when its domain meaning and value
   semantics support that role.
2. **Application commands** group the values for one use case. They do not group fields merely to
   reduce a parameter count. A command is a use-case input. It is not necessarily a DDD value object.
3. **Adapters and anti-corruption layers** translate external FIX and protobuf models before
   business behavior starts. An anti-corruption layer protects one model from another model's
   semantics. A DTO or value object within that layer is not itself the architectural pattern.
4. **Composition of data models** groups related fields in persistence models, event DTOs, WAL
   records, and configuration properties. Each group has a named responsibility or local invariant.
   Adapters map these groups to and from external formats. Composition alone does not make these
   infrastructure models domain objects.

An introduced parameter object must express a stable concept. A deep module must hide useful
complexity behind a simple interface and reduce what callers need to know. Splitting a class or
reducing a parameter count alone does not establish module depth. Durable Risk Admission is the
project's name for a business workflow, not a separate standard design pattern.

The implemented model is:

- `ReservationRequestIdentity`, `ReservationTerms`, and `ReserveOperation` express “reserve
  authority for this order”.
- `ReservationIdentity`, `ReleaseReservationOperation`, `ExecutionFill`, and `ApplyFillOperation`
  express reservation lifecycle commands. Separate `FillQuantity` and `FillPrice` types make
  positional exchange a compile-time error.
- `AdmissionCommand` combines `Identity`, `Order`, `FixIdentity`, and `RoutingReference`.
  Its typed UUID and FIX identifiers prevent command/order/account and sender/target/client-order
  exchanges.
- `SubmissionReference`, `FixSubmissionIdentity`, `PersistedFixIdentity`, `SubmissionOutcome`, and
  `SubmissionRejection` compose `SubmissionResult`. Each value owns one invariant and one reason
  to change.
- `AdmissionFailure` is the transport-independent reason an order cannot enter durable admission.
  Named factories expose the current risk validation vocabulary.
- FIX adapters use `FixOrderSnapshot` and `FixExecutionIdentity` as gateway-specific value objects.
  They support protocol translation without creating a shared order model.
- The earlier `V1OrderCommandAdapter` accepted the source protobuf command instead of eight
  positional fields. The repository retired that adapter under ADR-0007. It is not a current test
  target.

The recorded migration slices removed public positional constructors and methods with more than
seven parameters from their compatibility adapters. This evidence describes those historical
slices, not every current signature. For future work, migrate every in-repository caller to the
semantic interface before removing the positional Java member.

This intentionally breaks Java source and binary compatibility. Adapters preserve SQL, protobuf,
FIX, WAL, Kafka, and configuration contracts. PMD remains the only automated parameter-count gate.
A deprecated overload never qualifies for a semantic-boundary exception.

### CDC event metadata and delivery tracking

`CdcDeliveryEnvelope` groups six immutable Debezium values:

- Event identity.
- Message key.
- Payload bytes.
- Event type.
- Serialized headers.
- Publication time.

The store must compare these values with the authoritative Risk outbox. `CdcDeliveryObservation`
combines the envelope, exact Kafka position, and observation time. The public observation interface
therefore has three named values. The store keeps its exact-match invariant without a positional
eight-field constructor.

`CdcDeliveryMonitorContext` groups the metric identity, topic, and durable refresh clock for one
monitor. `CdcDeliveryMonitor` accepts that context, three behavior ports, and the transaction
boundary. Its constructor therefore has five parameters. Storage, progress, telemetry, and
transaction ownership remain explicit.

These carriers add stable CDC vocabulary and validation. They are not generic parameter bags or
exceptions to PMD's existing rule.

## Market Reference publication representation

`PublishedMarketSnapshot` remains a durable publication representation rather than a domain
aggregate. It composes snapshot identity, source provenance, canonical content, and publication
state. Canonical content remains a scalar because a one-field payload wrapper would be artificial.
The JDBC repository alone flattens and rehydrates the unchanged snapshot row.

The market snapshot source remains flat JSON. A source codec owns that external shape and
rehydrates a semantically grouped `SourceInstrument` before normalization produces a
`MarketInstrument`. JSON field names and nesting do not dictate the handwritten Java interface.

`MarketInstrument` combines instrument identity, trading rules, and an eligibility reason.
Trading rules combine board lot, tick table, and a reference-price band. The band requires the
reference price to lie between the lower and upper limits.

A well-formed source instrument remains in the snapshot even if its venue or security type is
unsupported. It has an explicit ineligibility reason. The model does not omit it or treat it as
malformed. Instrument identity retains a normalized venue value even when the system does not support
trading on that venue.

## SubmissionResult slice

The first migration slice grouped the durable submission outcome. `SubmissionResult` owns the
complete result from `SubmissionReference`, `FixSubmissionIdentity`, `PersistedFixIdentity`,
`SubmissionOutcome`, and its creation timestamp. It does not normalize identifiers or decide
acceptance. `SubmissionDecisionFactory` owns those decisions. The JDBC adapter only flattens these
values into the existing row and rehydrates them when reading it.

Test fixtures use a test-only semantic factory with complete named scenarios, such as accepted and
rejected outcomes. The factory exposes no generic builder, parameter bag, or arbitrary primitive
overrides that could create invalid combinations. Accepted outcomes have no rejection. Rejected
outcomes require stable, nonblank code and detail. Persisted FIX identity and its surrogate flag
remain inseparable.

The original completion criteria required:

- Removal of flat constructors and their suppressions.
- Migration of all production callers, fixtures, and tests.
- JDBC round-trips for accepted and rejected outcomes.
- Preservation of persisted FIX identity and surrogate state.
- Unchanged outbox payload and database schema.

## Account Authority lifecycle slice

Account Authority keeps Reservation, Account limit, and Account position as separate aggregate
roots. `AccountReservation` combines stable identity, account ownership, immutable reservation
terms, and a `ReservationLifecycle`. That lifecycle owns remaining and filled quantity, held
authority, outcome, and revision history.

The lifecycle rejects impossible combinations:

- Rejected reservations hold no authority and have a stable reason.
- Released reservations have no remaining authority.
- Applied reservations are fully filled.

Release ends unused authority only. It neither reverses a fill nor cancels the matching order.

`AccountLimit` and `AccountPosition` retain separate identity, state, and revision values.
A daily notional ledger is not a symbol-level inventory, although both use optimistic versions.
`AccountReservationApplicationService` owns the transaction and is the only supported reservation
write path. The repository removed the older direct-row reservation path. It did not create a
second, weaker lifecycle.

`AccountLifecycleOutbox` remains account-service infrastructure. It combines event identity,
destination, serialized payload, aggregate reference, and creation time. Only its JDBC adapter
flattens those values.

The original completion criteria required:

- Semantic Java interfaces for reservation, limit, position, legacy response, and outbox.
- Removal of the legacy direct writer.
- Account transaction tests for reserve, partial fill, release, rejection, and duplicate replay.
- Unchanged SQL and account lifecycle event shapes.

## Risk Admission journal slice

`AdmissionJournalEntry` combines a validated `AdmissionCommand`, an `AdmissionDeliveryRoute`, and
an `AdmissionLifecycle`. The command retains order facts, FIX business identity, and the opaque
`routingSnapshotId` from ingress. The delivery route contains the explicit partition, artifact
identity, and routing-algorithm version from the approved daily artifact.

The lifecycle uses decisions for each state, not a state enum with unrelated nullable fields:

- Pending admissions have no terminal decision.
- Accepted new orders have a reservation reference.
- Accepted cancellations explicitly require no reservation reference.
- Rejections have a stable, nonblank code and detail.

Revision and timestamps belong to that lifecycle.

`AdmissionResult` is an application result model. It contains Admission identity, decision,
artifact provenance, and delivery route. It does not copy journal revision history or full order
facts. Only the JDBC adapter flattens and rehydrates the journal row.

When an admission starts, risk-service resolves the approved daily artifact exactly once. It
persists the artifact identity, algorithm version, and explicit partition with the pending journal
entry. It publishes with the durable command identity as message key and that explicit partition.
Finalization and pending recovery reuse the recorded route. They do not resolve it again.

The Java route type checks two local invariants:

- An optional partition is non-negative.
- Artifact identity and algorithm version are present together.

The type still permits routes without an assigned partition or artifact provenance. `unassigned()`,
`assigned(int)`, and the single-partition constructor support isolated lifecycle and failure tests.
These routes are not valid for production delivery.

`DailyArtifactAdmissionRoutingResolver` supplies complete routes for new admissions.
`AdmissionJournalRowMapper` rejects persisted rows without routing provenance.
`AdmissionOutboxFactory` requires the partition, artifact identity, and algorithm version before
creating a Matching command. The resolver owns artifact approval and instrument-to-partition
membership checks. Constructing a route does not prove either condition.

This result model is not an independently maintained CQRS read model. It selects fields from the
journal entry for an application response. It has no separate projection store or update process.

The ingress `routingSnapshotId` remains optional and opaque. It is never an authority for routing.
The current schema excludes the retired nullable `routing_policy_id` compatibility column and
policy projection.

Current regression coverage must preserve:

- Semantic journal and result construction.
- JDBC round-trips for pending, accepted, and rejected states.
- Artifact identity and partition during recovery.
- Accepted outbox records with the command-identity message key and explicit partition.

## Risk Admission application modules

`OrderAdmissionApplicationService` owns synchronous orchestration for new orders and cancels.
It validates commands, applies backpressure, and requests account reservations outside a database
transaction. It delegates pending and terminal local work to `AdmissionLifecycleTransactions`.
That application module owns the transaction template, journal, outbox, event factory, and clock.

Beginning an admission uses one local transaction. Final journal state and its outbox record form
another atomic local transaction.

`PendingAdmissionRecovery` owns scheduled recovery. It retains bounded age and batch limits.
It requests remote reservations outside a transaction. It delegates terminal persistence to
`AdmissionLifecycleTransactions`. Failures remain eligible for a later retry.

`AdmissionJournalEntry` owns aggregate state transitions. Repositories remain thin adapters.

## Configuration properties grouped by responsibility

QuickFIX Gateway configuration has independently injectable property groups. These groups cover
file settings, runtime identity and capabilities, and risk-client resilience. Shared platform
configuration has separate modules for environment, Kafka, PostgreSQL, Redis, gRPC, routing,
observability, and market properties. Consumers receive only the capabilities they use.
Validation follows those same groups.

Existing Spring property keys and defaults remain unchanged. Multiple property records may bind
different subsets of an existing prefix. Issue #67 removed the former `QuickFixGatewayProperties`
migration facade. Issue #75 later removed the shared `PlatformProperties` facade after its final
production consumers migrated. No arbitrary root configuration group replaces it.

The repository completed the gateway-local QuickFIX split and shared capability migration one
service at a time. Context and configuration tests covered those changes. Shared startup
validation now uses the capability modules directly. Persistence tests check the environment
capability without a root facade.

## Validation and business invariants

Structural validation checks a value without loading authoritative state. It includes field
validity and local cross-field invariants. Distinct identifier types prevent positional exchanges;
they do not prove ownership of a stored reservation or approval of a routing artifact.

| Term                         | Owner            | Invariant                                                                                        |
|------------------------------|------------------|--------------------------------------------------------------------------------------------------|
| Reservation request identity | account-service  | request, order, and account identifiers are present and cannot be exchanged by type              |
| Reservation terms            | account-service  | symbol and side are present; quantity is positive; limit price is absent or positive             |
| Reservation identity         | account-service  | request, reservation, and order identifiers are present and have distinct UUID-backed types      |
| Execution fill               | account-service  | execution ID is present; sequence is non-negative when supplied; quantity and price are positive |
| Admission command            | risk-service     | required identity, order facts, FIX identity, and trading day are valid; the routing snapshot reference is optional and opaque |
| Submission reference         | risk-service     | request/order reference and normalized command type travel together                              |
| FIX submission identity      | risk-service     | sender, target, trading day, and client-order identifiers form one business identity             |
| Persisted FIX identity       | risk-service     | storage-safe identifiers and the surrogate flag cannot be separated                              |
| Submission outcome           | risk-service     | acceptance has no rejection; rejection always has a stable code and detail                       |
| Admission failure            | risk-service     | validation failure has a stable code and nonblank detail                                         |
| FIX order snapshot           | quickfix-gateway | execution-report mapping receives one coherent order view, not positional wire fields            |

State-dependent business invariants require authoritative state and its owning application flow:

| Business invariant | Owner and enforcement |
|---|---|
| Reservation and order belong to the requested account operation | account-service loads or locks authoritative reservation state and checks the supplied identities inside its transaction. |
| Reserve, fill, and release preserve account authority | account-service coordinates reservation, account limit, account position, and required event publication in the owning transaction. |
| An admission uses the approved artifact and assigned partition | risk-service resolves the instrument against the verified daily artifact and persists the complete route; recovery reuses that recorded route. |

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
composes persistence values without changing the persisted shape. The original
parameter-safety refactoring preserved flat `v1` JSON. The subsequent FIX security cutover requires
the complete authenticated FIX SessionID in WAL `v2`. With no legacy data, active v1 producers, or
external consumers to migrate, the approved pre-release retirement policy in
[ADR-0007](0007-retire-pre-release-compatibility.md) applies: only WAL v2 is read and written.
That later security decision supersedes the earlier v1 preservation requirement; the two
requirements apply to different migration stages.
Unsupported versions fail before recovery performs remote work; their bytes are not rewritten,
skipped, or silently upgraded. Existing v2 records retain restart recovery and session-owned
cancellation checks. Future persisted-shape changes still require a new schema version and an
explicit compatibility decision; grouping Java fields alone is not a version change. The codec
validates every decoded record as strictly as a newly appended record, including the permitted
FIX-message-type and command-type pairs.
The strict reader also rejects unknown or duplicate fields, trailing JSON tokens, and malformed or
unmappable character sequences before semantic rehydration. WAL bytes are UTF-8; it writes
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
`NewOrderRejectionResponder` collaborators encapsulate those path responsibilities.
`NewOrderDurableAdmission` owns the sequencing invariant that WAL persistence succeeds before Risk
submission starts. This responsibility explains its purpose without claiming that every extracted
collaborator is a deep module. Spring composes these concrete modules in
`QuickFixGatewayIngressConfiguration`; the public inbound dispatcher receives only the two path
handlers.

`NewOrderFixMessageHandler` currently has seven constructor parameters: four path collaborators,
the admission gate, a clock, and the session registry used for duplicate detection. This is a
reviewed seven-to-nine-parameter exception. Each dependency has a distinct role and type; combining
them in `Dependencies` would hide coordination without adding an invariant. Keep these dependencies
explicit and reassess the constructor if its responsibilities grow.

`OrderSessionState` remains a gateway-local correlation snapshot rather than an aggregate. It
composes the owning FIX session, account identity used for cancellation fallback, the existing
`FixOrderSnapshot`, and `OrderSessionLifecycle`. The lifecycle continues to own current order status
and outstanding cancel correlation. Eviction and distributed session ownership are outside this
parameter-safety slice.

`WalCommand` is command-specific: a new-order command requires `WalOrderTerms`; a cancellation
requires its cancellation identity and original client-order identity but has no order terms. The
codec writes the existing blank or unspecified fields for a cancellation, rather than making
those placeholders part of the Java model.

Replay is fail-fast. If a nonblank WAL line cannot be decoded or violates these invariants, replay
stops with its line number and leaves the WAL bytes unchanged for operator investigation. The
gateway never skips, silently repairs, or continues past a durable inbound command.

`WalRecordJsonCodec` is a package-private FIX Gateway adapter. It alone flattens and rehydrates
the `v2` JSON shape and applies WAL invariants. `WalAppender` owns line-oriented file I/O,
complete record writes, and file-content synchronization; it does not know JSON field names.
`appendAndFlush` writes until the record buffer, including the final LF byte, has no remaining
bytes. Only then does it call `FileChannel.force(false)`. A write that makes no progress or fails
must fail the append before synchronization. This is not an atomic-record-write guarantee: an I/O
failure can leave an incomplete record, which replay must report without changing the file. The
codec does not know files, channels, locking, or replay orchestration.

The original slice removed the public nineteen-argument `WalRecord` constructor and migrated every
in-repository factory, test, and caller. No deprecated positional constructor or compatibility
overload remains. JSON compatibility belongs solely to the codec.

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

## Current verification

Required verification includes value-object invariant tests, account transaction integration tests
for duplicate and oversized fills, risk validator tests for accepted and rejected outcomes, FIX
golden-message tests, WAL v2 round-trips, unsupported-version rejection, and `./gradlew staticAnalysis`.
WAL tests cover short writes, write failures, and signed 64-bit integer range checks. Invalid
records must report the physical line and leave the WAL bytes unchanged. Retired WAL v1 readers
and command adapters are not compatibility test targets. The refactoring must not
change SQL schema, protobuf schema, event payload, transaction boundary, idempotency key, or FIX
field output.

## Historical migration sequence

The original migration used five independently verifiable slices: durable submission outcomes, Account
Authority lifecycle state, Risk Admission journal state, QuickFIX ingress and WAL state, then
QuickFIX configuration and runtime policy. Each slice converted production callers, tests, and
supporting tools before removing positional Java members. The
[development reference](../development/domain-parameter-safety-refactor.md) retains the completion
evidence and its original scope. Later source changes require review against the current policy;
those historical inventories do not establish current constructor counts or compatibility support.

## References

- [PMD ExcessiveParameterList](https://pmd.github.io/pmd/pmd_rules_java_design.html#excessiveparameterlist)
  defines the inclusive reporting threshold.
- [Anti-Corruption Layer](https://learn.microsoft.com/en-us/azure/architecture/patterns/anti-corruption-layer)
  describes semantic isolation between models.
- [Ousterhout: Modular Design](https://web.stanford.edu/~ouster/cgi-bin/cs190-spring16/lecture.php?topic=modularDesign)
  explains simple interfaces, information hiding, and module depth.
- [FileChannel](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html)
  defines write completion and file synchronization separately.
- [Jackson JsonNode](https://fasterxml.github.io/jackson-databind/javadoc/2.11/com/fasterxml/jackson/databind/JsonNode.html)
  defines `isIntegralNumber()` and `canConvertToLong()`.

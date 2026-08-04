# Domain parameter-safety refactor

This document records the implementation scope after ADR 0002. The completed slices refactored every
handwritten production Java constructor and method with more than seven parameters to a shorter
semantic interface. PMD's `ExcessiveParameterList` rule, with its existing default threshold of ten,
is now the repository's sole automated parameter-count gate. Later slices must still preserve the
same semantic construction vocabulary rather than hide a wide boundary behind a broad exception. An
external shape may remain wide only at its flatten/rehydrate adapter; generated sources are excluded.

## Completed production migrations

| Previous boundary                                                                | Domain-shaped boundary                                                                                 | Compile-time protection                                                            |
|----------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| `ReserveOperation(String, String, String, String, Side, BigDecimal, BigDecimal)` | `ReserveOperation(ReservationRequestIdentity, ReservationTerms)`                                       | request/order/account IDs and quantity/price have distinct types                   |
| `release(String, String, String, String)`                                        | `release(ReleaseReservationOperation)`                                                                 | request/reservation/order IDs cannot be exchanged                                  |
| `applyFill(... seven values ...)`                                                | `applyFill(ApplyFillOperation)`                                                                        | reservation identity, execution ID, sequence, quantity, and price are named values |
| flat 15-field `AdmissionCommand` construction                                    | `AdmissionCommand(AdmissionIdentity, AdmissionOrder, AdmissionFixIdentity, AdmissionRoutingReference)` | command/order/account UUIDs and FIX identities are distinct types                  |
| flat 12–15-field `SubmissionResult` construction                                 | composed `SubmissionResult` domain values                                                              | accepted/rejected outcome and storage-safe identity own their invariants           |
| flat nine-field Risk Submission outbox event descriptor                          | `OutboxEvent(EventInfo, Routing, SerializedPayload, AggregateRef)`                                   | event identity, delivery route, payload type, and aggregate provenance are named   |
| `buildPendingNew` / `buildRejected` positional FIX values                        | `FixOrderSnapshot` plus `FixExecutionIdentity`                                                         | order ID, ClOrdID, symbol, quantity, and ExecID cannot be exchanged                |
| eight-value v2-to-v1 helper                                                      | adapter receives the source protobuf command                                                           | compatibility mapping is explicit and source-oriented                              |
| eight-parameter new-order handler and seven/eight-parameter inbound composition  | deep new-order path modules plus a two-handler inbound dispatcher                                     | ingress owns path behavior while Spring owns concrete composition                  |
| eight-field `QuickFixGatewayProperties` configuration root                       | file, runtime-capability, and risk-client property modules                                             | each capability binds the unchanged namespace independently                        |
| eight-collaborator `OrderAdmissionApplicationService`                            | six-value coordinator plus `AdmissionLifecycleTransactions`                                            | local transaction ownership and journal/outbox atomicity have a named seam        |

No positional overload with more than seven parameters remains inside the completed slices as a
compatibility adapter. Migrate all in-repository production callers, fixtures, and neighboring
callers, then remove the member while preserving its external SQL, protobuf, FIX, WAL, Kafka, or
configuration contract through adapters. Findings in later slices remain migration work, not
intentional exceptions to the policy.

## Wide external shapes under migration

| Slice | External shape | Required semantic representation and adapter containment |
|---|---|---|
| 1. Durable submission outcomes | submission journal row and result payload | `SubmissionReference`, FIX identities, persisted identity, and outcome remain the only Java construction vocabulary; adapters flatten them. |
| 2. Account Authority lifecycle state | reservation, limit, position, and legacy result rows | identity, terms, quantities, outcome, and audit/version groups compose the Java model; the transaction-owning application module remains the seam. |
| 3. Risk Admission journal state | admission journal row and result payload | identity, order facts, FIX identity, routing, decision, and audit groups compose the Java model; JDBC flattens and rehydrates them. |
| Risk Submission outbox event descriptor | append-only outbox event before header enrichment | event information, delivery routing, serialized payload, and aggregate reference compose the Java construction vocabulary; the abstract factory adds transport headers. |
| 4. QuickFIX ingress and WAL state | raw FIX message, WAL row, and session correlation | the adapter contains protocol fields; durable intent is composed from session/command identity, order terms, and audit groups. |
| 5. QuickFIX configuration and runtime policy | configuration namespace and runtime values | capability and resilience policy groups compose the Java model; configuration binding maps the unchanged namespace. |

## Verification boundary for Issues #39 and #44

The completed verification boundary is deliberately limited to the two slices named by the parent
specification:

- Account Authority `authority` and `reservation` production code, its JDBC adapters, gRPC adapter,
  and transaction/outbox tests.
- Risk Admission `admission` production code, its journal/outbox adapters, and route-reuse tests.

The completed `SubmissionResult` predecessor slice is tracked by Issues #33–#37, and the completed
Risk Submission outbox event descriptor slice is tracked by Issue #46. Remaining Risk Submission
members, QuickFIX WAL, Market Reference snapshots, and shared platform
configuration are separate parameter-safety surfaces. Any remaining wide member there is follow-up
work; it is not an exception to the PMD gate and is not a reason to keep Issues #39, #44, or #46
open.

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

## Risk Submission outbox event descriptor

`OutboxEvent` remains Risk Submission infrastructure rather than a domain aggregate. It composes
event information, delivery routing, serialized payload, and aggregate reference before the abstract
factory enriches the event with common transport headers and produces `OutboxRecord`. The existing
factory creation seam remains the sole construction and test surface; JDBC continues to flatten the
resulting `OutboxRecord` into the unchanged append-only outbox row.

`SerializedPayload` owns its byte array defensively and carries the protobuf message type with the
bytes. `OutboxRecord` remains the owner of persisted-row validation and header envelope validation.
The accepted and rejected message payloads, message key, explicit Kafka partition, headers, SQL
binding, and CDC behavior remain unchanged. The PMD gate is the only automated parameter-count
verification for this source directory.

## Slice 4: QuickFIX ingress durable path

The QuickFIX ingress slice keeps the public application seam at `InboundFixMessageHandler`, which
dispatches only by FIX message type to `NewOrderFixMessageHandler` or
`CancelOrderFixMessageHandler`. `NewOrderFixMessageHandler` now receives four behavior-rich
modules: `NewOrderCommandPreparer` validates and normalizes the message,
`NewOrderDurableAdmission` appends the WAL before invoking Risk Admission,
`AcceptedNewOrderResponder` registers the session and performs the accepted FIX and compatibility
responses, and `NewOrderRejectionResponder` renders malformed-input rejection reports.

`QuickFixGatewayIngressConfiguration` composes those concrete modules and the cancel path. The
dispatcher no longer knows about WAL, risk, session registry, compatibility publication, mapper, or
clock dependencies. Existing FIX fields, v1 WAL JSON, risk response text, session correlation, and
compatibility publication remain unchanged; tests continue to exercise them through the ingress and
QuickFIX certification seams.

## QuickFIX configuration and Admission transaction slices

QuickFIX gateway configuration keeps the existing `simplematch.quickfix-gateway` namespace while
binding it into three independently injectable records: `QuickFixGatewayFileProperties` owns the
two file paths, `QuickFixGatewayRuntimeProperties` owns identity and capability switches, and
`QuickFixGatewayRiskClientProperties` owns deadline, retry, and breaker settings. Their constructors
have two, five, and three parameters respectively. Consumers and validation receive only the
capability they use; the former `QuickFixGatewayProperties` facade is removed.

`OrderAdmissionApplicationService` owns validation, backpressure, remote account reservation, and
bounded recovery orchestration. `AdmissionLifecycleTransactions` owns the five-value transaction
seam: journal, outbox, event factory, clock, and the bounded `TransactionTemplate`. Its pending
operation and terminal operation each execute inside one local transaction; terminal journal state
and its outbox record commit together. Recovery performs account RPC outside that transaction and
delegates only terminal local work to the module. JDBC repositories remain thin adapters.

## Review checklist

A new handwritten production Java method or constructor should be reviewed for a shorter semantic
interface before merge; PMD blocks members that exceed its existing default threshold. Review must
answer these questions:

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

## Completion evidence for Issues #39 and #44

The Account Authority and Risk Admission slices were verified on 2026-07-31:

- A source inventory over Account Authority `authority`/`reservation` and Risk Admission `admission`
  production Java reported `completed-slice-wide-members=0` for handwritten constructors and
  methods over seven parameters.
- `./gradlew -q :services:account-service:test :services:risk-service:test --rerun-tasks` passed.
- `./gradlew -q test --rerun-tasks` passed for the repository test suite.
- `./gradlew -q certificationTest --rerun-tasks` passed for the QuickFIX certification smoke gate.
- `./gradlew -q staticAnalysis` passed.
- The completed-slice source inventory recorded no handwritten constructors or methods over seven
  parameters; this is historical refactoring evidence, not a current Checkstyle gate.
- `bash scripts/test-check-markdown-links.sh` passed after the canonical-document and forwarding-page
  update.

The build still emits existing compiler and runtime warnings, including Error Prone
`SelfAssignment` warnings in semantic record constructors; they are warnings, not failed gates.
The verification does not claim that later QuickFIX, Market Reference, shared configuration, or
legacy Risk Submission slices are complete.

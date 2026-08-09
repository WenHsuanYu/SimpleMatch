# Cross-Cutting Transaction and Consistency Policy

This is the canonical execution and phase-gate policy for transaction ownership and consistency. It
describes required future behavior, not current runtime behavior. Cross-service outcome,
reconciliation, identity, and client/operator error-boundary rules are defined separately in
[Consistency, Recovery, Identity, and Error Boundaries](../services/docs/platform/consistency-recovery-identity-and-errors.md).

## TP-1 — Transaction Ownership and Atomicity

Every all-local business use case that changes authoritative state defines one explicit atomic
outcome and one application-service-owned transaction boundary. That boundary includes all local
mutations that must succeed or fail together:
authoritative state, reservations, aggregate sequences, inbox state, and outbox records where
applicable.

Review the design in this order: define the atomic outcome; identify the owning application method;
separate work inside and outside the transaction; select the Spring mechanism; and prove commit,
rollback, duplicate-delivery, and concurrency behavior with integration tests. `@Transactional` by
itself is not acceptance; the persisted result after success, failure, retry, and duplication is.

## TP-2 — Default Transaction Mechanism

Use `@Transactional` by default on an externally invoked public concrete application-service method
when the complete use case is an all-local database transaction. The method represents a meaningful
business operation, such as reserving funds, admitting an order with its outbox event, applying a
lifecycle event, publishing a snapshot, or updating a projection from an inbox event.

The method must be invoked through the Spring-managed proxy. Do not rely on self-invocation to
activate a transaction or different propagation behavior.

## TP-3 — Narrow Programmatic Transactions

Use `TransactionTemplate` only when a deliberately narrow database critical section must exclude
request-shape validation, pure state-independent calculation, expensive serialization, file or
network I/O, remote HTTP or gRPC calls, Kafka publication outside an outbox mechanism, or other slow
or unbounded work. It wraps only the database-dependent section and is never an excuse for an
unclear application-service method.

## TP-4 — Validation Placement

Validate syntax, required fields, static ranges, enums, protocol shape, and deterministic
calculations before a transaction. Validate available balances or positions, state transitions,
account versions, aggregate sequences, duplicate events, snapshot versions, and other current-state
invariants inside the same transaction as the mutation.

Protect concurrency-sensitive invariants with a database constraint, conditional update, optimistic
version, or explicit lock. A prior Java query alone is not sufficient protection against a race.

## TP-5 — Repository Responsibility

Repositories may define persistence-operation-level requirements, but never own a cross-repository
business transaction. They load, insert, update, delete, or lock state as directed by the
transaction-owning application service; repository metadata is not a substitute for that enclosing
boundary.

## TP-6 — Local and Distributed Atomicity

A Spring database transaction is atomic only for its participating local resources. It does not make
remote HTTP, gRPC, Kafka, Redis, or another service's database atomic. A remote side effect
therefore requires an explicit outbox, idempotent command handling, inbox deduplication, conditional
state transition, bounded retry, compensation, reconciliation, or persisted-intent saga. Never hold
a local transaction open across a remote call merely to create the appearance of distributed
atomicity.

## TP-7 — Outbox Atomicity

When a committed local state change requires a downstream event, that state and its outbox record
commit in one local transaction. The system must never commit authoritative state without its
required durable event, nor an event for state that did not commit. Serialize data independent of
generated state before the transaction; construct the final envelope inside only when it needs
generated identifiers, sequences, versions, or authoritative state.

## TP-8 — Inbox Atomicity and Idempotency

For an asynchronously consumed event, inbox deduplication, authoritative or projection mutation,
generated outbox records, and inbox completion commit atomically when they form one processing
outcome. Do not mark an inbox complete before all required mutations commit. A duplicate produces
the same final state without repeating non-idempotent effects. A protocol-defined business rejection
may be a durable processed outcome; infrastructure failure leaves the event eligible for retry.

## TP-9 — Exception and Rollback Semantics

Each transaction owner defines its rollback failures. Do not catch an exception and return apparent
success when the outcome should fail; translated exceptions preserve rollback semantics. Checked
exceptions need an explicit rollback rule when they represent transaction failure. Do not catch
`Exception` or `Throwable`
indiscriminately, or return success after marking a transaction rollback-only.

## TP-10 — Concurrency Control

Every transaction that modifies shared authoritative state documents a concrete strategy: optimistic
versioning, pessimistic locking, conditional SQL, unique constraints, aggregate-sequence checks,
per-account serialization, or per-instrument ordering. Design it with the transaction boundary;
annotation alone does not prevent lost updates, duplicate reservations, stale writes, or invalid
transitions.

## TP-11 — Transaction Duration and External Work

Keep transactions short. Remote calls, blocking network operations, file access, large parsing,
expensive serialization, unbounded computation, Kafka waits, and retry loops normally remain
outside. Every owner has a bounded execution time:
it may inherit a documented service-level timeout, while lock-sensitive or externally invoked
operations require a documented tighter timeout. Never rely on an unknown framework default.

## TP-12 — Transaction Verification

Prove correctness with database-backed integration tests, not annotation review or mocked
repositories. Each phase maps each applicable case below to a named test, and marks every
inapplicable case `N/A` with a reason:

- successful atomic commit;
- failure during the first mutation;
- failure during a later mutation;
- outbox insertion failure;
- inbox completion failure;
- constraint violation;
- optimistic-lock or conditional-update conflict;
- duplicate command or event delivery;
- checked and unchecked exception behavior;
- transaction rollback;
- consumer restart after partial processing;
- concurrent requests for the same aggregate; and
- absence of partial authoritative state.

Spring transaction interception is cross-cutting framework infrastructure, not the repository's
business responsibility.

## Phase-Specific Transaction Acceptance Criteria

### Phase 5 — Market Snapshot and Outbox

The public snapshot-publication operation owns one transaction for version allocation, metadata,
complete content or its immutable reference, activation, publication metadata, and the outbox
record. Parse, validate, normalize, build, and serialize independent data before it. Keep
current-version and active-state checks, allocation, persistence, activation, and generated-version
envelope construction inside. Outbox or snapshot failure exposes no new active snapshot. Use
versioning, locking, or uniqueness to prevent conflicting active versions; duplicate imports have
deterministic existing-result or new-version behavior.

### Phase 6 — Reservation, Lifecycle Processing, and Inbox

Each public account reservation or event-processing operation owns the outcome:
inbox state, account and reservation mutation, version, sequence, lifecycle result, account outbox
record, and inbox completion. Decode and statically validate outside; duplicate checks, account load
and concurrency enforcement, available-balance or position validation, mutation, sequence checks,
outbox, and inbox completion inside. Insufficient funds may atomically commit a durable rejection
without a reservation. Infrastructure failure leaves the event retryable; per-account database
concurrency prevents duplicate mutation.

### Phase 7 — Order Admission and Outbox

Order admission is a persisted-intent saga rather than one distributed transaction. The first local
critical section claims `command_id` idempotency and records durable `PENDING` before the remote
Account reservation call. The Account RPC executes outside a Risk database transaction and reuses
the same command identity as the reservation request identity. A later local transaction commits the
terminal `ACCEPTED` or `REJECTED` journal state together with the required outbox event.

Duplicate commands reproduce the authoritative journal result, stale `PENDING` work is recovered by
the owning Risk saga, and transport uncertainty is resolved through reconciliation rather than being
converted into a business rejection. Gateway recovery must not treat Risk `PENDING` as retry
permission. The exact cross-service recovery decisions are defined in the platform consistency and
recovery policy linked at the top of this document.

### Phase 11 — Account Lifecycle Integration and Outbox

Each public account lifecycle processor owns inbox state, account and reservation settlement or
release, account version, aggregate sequence, lifecycle outcome, outbox record, and inbox completion
as one local outcome. Duplicate events reproduce the stored result; sequence gaps are quarantined
without advancing state; database or outbox failure leaves the event retryable. Per-account locking,
versioning, or conditional updates prevents double settlement or release.

### Phase 12 — Projection Processing and Inbox

Each public projection processor owns inbox state, sequence or projection-version check, projection
mutation, checkpoint, optional generated outbox state, and inbox completion. Redis publication
occurs after durable PostgreSQL commit and is rebuilt or retried explicitly. Each projection
declares whether it is replacement-based, versioned upsert, incremental, sequence-sensitive, or
rebuildable. Duplicates cannot double-apply, stale events cannot overwrite newer state, and sequence
gaps pause, quarantine, or resynchronize.

### Phase 13 — Projection Recovery, Replay, and Inbox

Each public replay or recovery operation owns one bounded transaction per batch, aggregate, or
checkpoint; a full replay is never one unbounded transaction. Projection changes, sequence progress,
replay or inbox deduplication, recovery metadata, and permitted outbox state commit together. The
phase declares whether it uses live inbox state, a separate replay checkpoint, shadow projections,
and whether publication is suppressed. Failure resumes from the last committed checkpoint, and
shadow cutover is atomic.

## Refactoring-Plan Review Rule

Every phase that introduces or changes persisted authoritative state, reservations, inbox
processing, outbox publication, aggregate sequencing, checkpoints, or projections includes a
`Transaction Acceptance Criteria` section that references this policy instead of restating it. It
must define:

| Required field               | Required content                                           |
|------------------------------|------------------------------------------------------------|
| Applicable policy            | TP-1 through TP-12 reference                               |
| Transaction owner            | Public application-service or consumer operation           |
| Atomic writes                | State that commits or rolls back together                  |
| Work outside the transaction | Parsing, static validation, external calls, expensive work |
| Work inside the transaction  | Database-dependent invariants and mutations                |
| Failure outcome              | Exact permitted state after failure                        |
| Retry and idempotency        | Duplicate commands, events, and re-execution behavior      |
| Concurrency control          | Concrete mechanism and losing-request outcome              |
| Timeout policy               | Documented service default or tighter operation timeout    |
| Verification                 | PostgreSQL integration tests and TP-12 mapping             |

Phases 5, 6, 7, 11, 12, and 13 are mandatory known applications. The list is not exhaustive: add
equivalent criteria before implementing any later persisted consistency boundary.

## Refactoring-Plan Phase Gate

A phase cannot begin implementation until its transaction criteria are complete. It cannot be marked
complete until its mapped PostgreSQL-backed integration tests pass.
`scripts/check-transaction-acceptance-criteria.sh` checks documentation structure only; review and
integration tests remain authoritative for mechanism selection and behavior.

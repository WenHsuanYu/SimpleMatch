# Data Model

This is the canonical target specification for SimpleMatch data ownership and authoritative state.
It describes the intended model, not a statement that every table or projection already exists.

## Ownership and authority

Each business concept has one write owner. Other services obtain it through a service boundary or an
event-derived projection; they do not write the owner's database tables.

| Concept                                     | Write owner       | Authoritative form                                | Read use outside the owner                                 |
|---------------------------------------------|-------------------|---------------------------------------------------|------------------------------------------------------------|
| Client order command and risk decision      | `risk-service`    | Durable admission state and its integration event | Ordered matching input and audit projection                |
| Canonical account identity                  | `account-service` | UUID-backed `AccountId`                           | Validated and propagated unchanged across boundaries       |
| Account limits, positions, and reservations | `account-service` | Account state                                     | gRPC decision or a purpose-built projection                |
| Order-book state and matching sequence      | `matching-engine` | Deterministic per-instrument matching state       | Execution events and rebuildable views                     |
| Execution                                   | `matching-engine` | Execution result event                            | Persistence, client reporting, and market-data projections |
| Query and audit views                       | `persistence`     | Rebuildable projections                           | Query APIs, reporting, and recovery                        |

`quickfix-gateway` owns FIX session context, client-message normalization, the ingress command WAL,
and its local recovery-state sidecar. It does not become the authority for Risk outcomes, Account
identity, account state, matching state, or execution state.

## Identity and lifecycle rules

- A client order identity (`ClOrdID` / `cl_ord_id`) is preserved across the synchronous admission
  boundary. It is not silently repurposed as a database primary key or a Kafka offset.
- FIX-facing and WAL-facing `OrderID(37)` remains `O-<ClOrdID>`. The Risk v2 boundary uses a separate
  deterministic opaque UUID for internal order identity so internal typing does not redefine the FIX
  protocol surface.
- `command_id` identifies one admission attempt and is the idempotency and reconciliation identity.
  Retry and recovery reuse the original value rather than generating a new command.
- Canonical `account_id` is a UUID-backed identity owned by `account-service`. Gateway and Risk
  validate or propagate that value; they do not derive, alias, or translate account identity.
- An order identity identifies the accepted order through matching and client reporting. An
  execution identity identifies one immutable fill or lifecycle event.
- Event identifiers identify delivered integration records. They support consumer deduplication but
  do not replace aggregate ownership or ordering.

The cross-service retry, recovery, and identity rules are defined by
[Consistency, Recovery, Identity, and Error Boundaries](consistency-recovery-identity-and-errors.md).
Identifier wire shapes remain contract concerns; this page owns semantic authority and lifecycle
roles.

## State, events, and projections

The target is event-driven with service-owned authoritative state. A successful local transition
that must be visible outside its owner is paired with an outbox record in the same transaction.
Kafka consumers may receive a record more than once, so each projection records enough identity or
checkpoint state to make application idempotent.

Risk admission is authoritative in the durable admission journal. Gateway WAL and recovery-sidecar
state support ingress crash recovery but do not replace that Risk-owned business outcome.

### Outbound integration-event rule

A new service requires a service-local transactional outbox only when it both owns authoritative
state and has a committed state transition that must be delivered reliably to another bounded
context. Its outbox record belongs to the same owner schema and local transaction; Debezium or an
approved connector publishes that record. A service does not require an outbox or connector merely
because it consumes Kafka, reads a projection, or streams an already published event. If it later
owns such a state transition and outbound integration event, this rule applies to that new boundary.

Projections may be rebuilt from an agreed checkpoint and are allowed to be eventually consistent.
They must not decide admission, risk, matching fairness, or another authoritative write outcome. A
later move to event-authoritative aggregates requires an explicit append-only event history,
versioning rule, and rebuild contract; Kafka use alone does not make a model event-sourced.

## Boundary rules

- A service may persist only its own schema and migration history.
- Cross-service relationships use stable identifiers and APIs or events, not cross-schema foreign
  keys or write-path joins.
- `aggregate_type` and similar event metadata describe business semantics; they are not database
  schema names.
- Retention, privacy classification, and audit requirements are specified per authoritative concept
  before adding a new persistent store.

## Target versus execution state

This page is the authority for target data ownership. SQL table layouts, migration inventory, and
rollout evidence remain in the
[database implementation guide](../../../docs/database-architecture.md).

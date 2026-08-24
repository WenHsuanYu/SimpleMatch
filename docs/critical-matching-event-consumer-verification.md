# Critical Matching Event Consumer Verification

This record captures repository-level verification for the critical
`matching.events` consumers tracked by issues #130, #131, and #132. It
supplements the Phase 1 remaining-work inventory; it does not replace the
repository-owned local production-like certification required before those
capabilities can be marked complete.

## Verification scope

Verification is performed through the application Interfaces that own each
consumer's durable business outcome. Tests use real local database Adapters when
transaction behavior is part of the contract and replace only true remote system
dependencies. Restart tests build application configuration from durable database
state instead of reconstructing in-memory status directly.

### Persistence

`MatchingEventPersistenceApplicationServiceTest` exercises the Persistence
application Interface with Flyway, H2 in PostgreSQL compatibility mode, JDBC,
and a real transaction manager. A simulated failure before transaction commit
proves that the final-event inbox, immutable trade, both order-fill legs, order
projections, and partition progress roll back together. Replaying an identical
committed event proves that permanent trade facts remain single-instance while
transport progress advances to the later Kafka offset.

`PersistenceMatchingEventProgressRestartTest` starts the consumer configuration
from durable progress and quarantine rows. It verifies that the next Kafka
position is reconstructed from the last processed offset and that an unresolved
quarantine restores both readiness state and the delivery controller's paused
offset. `PersistenceMatchingEventConsumerTest` verifies that a restored paused
offset is checked before the Persistence application transaction, so the inbox,
trade facts, projections, and progress cannot advance while operator recovery is
still required.

### Account

`FinalMatchingEventAccountAuthorityIntegrationTest` exercises the Account-owned
`FinalMatchingEventAccountCommand` Interface with the real Account Authority and
final-event inbox in one local transaction. Maker and taker fill effects are
applied exactly once, an IOC terminal outcome releases only unused buyer
authority, and a FOK terminal outcome releases the entire unused reservation.
An identical replay does not create a second lifecycle outbox effect.

`AccountFinalMatchingEventProgressRestartTest` reconstructs committed progress
and unresolved quarantine state from the Account database. The restored
quarantine is installed in both consumer status and the delivery controller.
`FinalMatchingEventAccountConsumerTest` verifies with the real Account
application transaction that the blocked offset is sought and paused before any
reservation effect, inbox write, progress update, or Kafka acknowledgement can
occur.

Account consumer status derives next Kafka positions, oldest-unprocessed age,
and quarantine state from one transport-progress Module. The Protobuf-to-Account
translation remains an Adapter at the external Seam, so Matching wire details do
not become part of the Account application Interface.

### QuickFIX Gateway

`FinalMatchingEventFixLifecycleIntegrationTest` verifies durable IOC, FOK,
rested, and expired FIX lifecycle intents through the final-event application
Interface. Stable event identity produces stable lifecycle execution identity,
and an identical replay does not create a second delivery intent.

`FinalMatchingEventFixAdmissionIntegrationTest` exercises the live admission
path from a FIX `NewOrderSingle`, through the Gateway WAL and Risk command
mapping, to a final `ORDER_RESTED` event. The Risk submission Adapter is the only
remote dependency replaced by the test. The canonical Risk/Matching order UUID
from the accepted command resolves the original FIX session, while the durable
FIX snapshot retains `O-<ClOrdID>` for FIX `OrderID(37)`.

`WalReplayCanonicalOrderIdentityIntegrationTest` exercises terminal accepted WAL
recovery through `WalReplayService`. It proves that restart reconstruction maps
the same canonical order UUID back to the owning FIX session and original
FIX-facing order snapshot without another Risk submission or reconciliation
request.

`QuickFixFinalMatchingEventProgressRestartTest` reconstructs committed progress
and unresolved quarantine state from Gateway-owned durable tables.
`FinalMatchingEventFixConsumerTest` verifies with the real FIX delivery
application transaction and JDBC delivery store that a restored quarantine
blocks before the event inbox, delivery intents, progress, or Kafka
acknowledgement can advance.

## Recovery Module design

`CriticalDeliveryController` remains the Module that owns in-place retry,
quarantine, paused-offset, and exact-offset recovery policy. Durable quarantine
Adapters provide unresolved positions during startup, and each consumer
configuration restores those positions before constructing the active consumer
Interface. Kafka consumers ask the controller whether a partition is already
blocked before entering their application transaction.

This keeps recovery behavior behind one small Interface. Persistence, Account,
and QuickFIX obtain Leverage from the same policy while context-specific SQL
remains local to each JDBC Adapter. The placement also preserves Locality:
restart semantics do not have to be reimplemented inside each application
transaction.

`CriticalConsumerProgressTracker` centralizes next-position, pending-record age,
and quarantine status behavior used by the three consumer-status Modules. It is
an internal Implementation detail rather than a new external Seam.

## FIX identity Module design

`OrderSessionRegistry` is the identity-correlation Module for Gateway session
state. Its Interface exposes accepted-order registration, session-aware ingress
lookup, cancellation registration, execution lifecycle operations, and final
order-state lookup. Its Implementation hides the distinction between the
canonical Risk/Matching UUID and the session-scoped FIX-facing `OrderID` index.

This placement gives live admission, final-event planning, legacy execution
projection, and WAL recovery Leverage from one identity policy. It also provides
Locality: identity derivation and correlation changes remain inside one Module
instead of being reproduced across callers. No additional external Seam or
single-Adapter indirection was introduced.

A FIX-facing order identifier is not interchangeable with the canonical
Risk/Matching order identifier. `OrderID(37)` remains `O-<ClOrdID>`, while the
canonical internal UUID includes FIX session identity, the Asia/Taipei trading
day, and the original `ClOrdID`. Session-aware lookup therefore prevents two
independent FIX sessions that reuse the same `ClOrdID` from sharing order state.

## Kubernetes configuration verification

The repository Kubernetes configuration now explicitly enables the final
Matching Event consumer for Account, Persistence, and QuickFIX. The application
resource defaults remain disabled, so repository deployment configuration must
opt into the critical consumer deliberately.

`scripts/test-critical-consumer-kubernetes-config.sh` verifies that requirement
through the ConfigMap `application.yaml` Interface. It reports the exact missing
ConfigMap or configuration path rather than relying on implementation-specific
parsing failures. `Local Resource Lifecycle CI` executes this contract together
with the existing image, rendering, production-like configuration, resource
lifecycle, and live kind registry/resource checks.

The TDD tracer first failed in Local Resource Lifecycle CI #141 because
`quickfix-gateway-config` did not contain
`simplematch.quickfix-gateway.final-matching-events.enabled`. After enabling that
setting, Local Resource Lifecycle CI #142 passed both the repository contracts
and the live kind registry/resource smoke.

This proves the retained Kubernetes configuration activates the consumer. It is
not evidence that a deployed QuickFIX session has completed disconnect, resend,
or PostgreSQL/Kafka failure-and-restart certification.

## Domain and architecture records

No `CONTEXT.md` change is required. This implementation does not introduce a new
domain term; it enforces existing order-identity, transaction, recovery, and
deployment requirements.

No ADR is required. The changes do not establish a new hard-to-reverse
architectural decision. They implement already accepted identity, partition
ordering, durable recovery, and deployment behavior.

## Continuous integration evidence

Before final history curation, the complete implementation and Kubernetes
configuration tree passed the following GitHub Actions runs on 2026-08-24:

- Java CI #250: repository-wide static analysis, the complete Java test suite,
  and QuickFIX certification passed.
- Flyway CI #215: Flyway contracts and PostgreSQL smoke checks passed.
- CDC CI #360: diff hygiene, connector contracts, Matching topic checks, Kafka
  durability checks, and the live CDC contract check passed.
- Local Resource Lifecycle CI #142: shell configuration/lifecycle contracts and
  the live kind registry/resource smoke passed.

The Java suite includes the Persistence transaction/replay tracer, Account
Authority final-event tracer, QuickFIX lifecycle tracer, live FIX admission
identity tracer, WAL identity restart tracer, durable consumer-progress restart
tests, unresolved-quarantine restart tests, oldest-unprocessed-age checks, and
pre-transaction blocking tests described above.

History curation must preserve the verified implementation tree. The curated PR
head must execute its applicable CI workflows again before review; final-head run
identifiers belong in the pull request description so recording them does not
create another documentation-only commit after CI completes.

## Remaining completion gate

Issues #130, #131, and #132 remain open until the repository-owned local
production-like environment executes the required PostgreSQL/Kafka failure and
restart scenarios. That gate must use the retained production-shaped dependency
and deployment path rather than treating H2, mocked remote dependencies, or
GitHub Actions as deployment certification.

QuickFIX socket delivery remains at least once by design. Repository tests prove
stable delivery and execution identities plus durable pending-intent recovery,
but the remaining local certification must still exercise disconnect, resend,
and restart behavior through the production-shaped FIX session path before #132
is considered complete.

Gateway operational admission composition remains separate GO-1 work. The three
consumer-status Modules now expose readiness, next Kafka positions,
oldest-unprocessed age, and quarantine state; an infrastructure Adapter still
has to combine those facts with Kafka end offsets, trading identity, and
observation time for the Gateway operational Interface. That aggregation does
not belong inside the Account or Persistence Modules.

Until the local failure/restart and FIX-session certification is executed and
retained as evidence, the capabilities remain `PARTIAL` even though their
repository implementation, deployment configuration, and CI checks pass.

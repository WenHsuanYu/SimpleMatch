# Market Reference owns routing policies

Status: accepted

## Context

Risk Admission currently resolves an instrument to an `orders.validated` Kafka partition from a
service-local JSON file. It persists the resolved partition so recovery does not recompute the
route, but the JSON file is not an authoritative cross-service artifact. Its string snapshot
identifier is also unrelated to the optional UUID `routingSnapshotId` supplied at ingress. Matching
therefore cannot verify which policy selected an admitted order's partition.

Market Reference already owns the versioned instrument facts consumed by Risk Admission and
Matching. Routing has a different change reason from instrument eligibility and trading rules, so
putting partition assignments inside a market snapshot would couple market changes to deployment
topology changes.

## Decision

Market Reference owns and publishes a separate immutable Routing Policy artifact. Each artifact has
an opaque UUID `routingPolicyId`, an effective interval, the Market Snapshot used to validate its
instrument universe as separate provenance, the expected `orders.validated` partition count, and a
complete instrument-to-partition assignment. Its identity is distinct from both Market Snapshot
identity and the ingress `routingSnapshotId`; those identifiers are never reinterpreted as one
another.

Normal operation publishes and completely preloads one policy before Risk Admission begins
accepting orders for the trading day. Policies may have non-overlapping intraday effective intervals
for exceptional additive changes, but an instrument already assigned during a trading day cannot
move to another partition that day. Taiwan cash-equity trading is modeled as one continuous regular
session rather than morning and afternoon policy batches. Consumers stage and validate a complete
artifact before activating it atomically; partial policies are never visible.

Market Reference distributes published policies through its outbox. Risk Admission and Matching
consume them into durable local projections, so neither service makes a synchronous Market
Reference call in its order-processing path. Both services remain unready until they have validated
and activated the required policy. A still-effective local policy remains usable during a Market
Reference outage, but missing, expired, incomplete, or invalid policy state fails closed. Consumers
must not hash an unknown symbol, invoke Kafka's default partitioner, or fall back to the old local
JSON file.

Risk Admission resolves an instrument exactly once when beginning admission. The pending admission
transaction atomically persists the authoritative `routingPolicyId`, the explicit partition, and
the pending state before Account Authority or later finalization work. Retries and recovery reuse
those persisted values even if another policy has since become active. The accepted
`orders.validated` event uses the instrument as message key, targets the persisted partition, and
carries the policy identity.

Matching maintains its own local view of the published policy and verifies each accepted event. If
the referenced policy has not arrived, Matching pauses consumption and retries after its projection
catches up. If the policy is known and the event arrived on the assigned partition, Matching
processes it. A known-policy partition mismatch is an invariant violation: Matching stops the
affected partition for operator investigation and never silently reroutes the order.

## Consequences

Rollout is additive and consumer-first. Persistence and event contracts first gain a nullable
`routingPolicyId`; Matching support is deployed before Risk emits it. The complete policy is then
published and preloaded before new admissions require both identity and partition. Legacy pending
admissions recover using their already-persisted partition without recomputation. After no such
admissions remain, the local JSON resolver and temporary nullable compatibility path are removed,
and new admission storage requires the policy identity.

This design preserves deterministic per-instrument ordering and makes policy provenance auditable,
at the cost of a pre-open readiness dependency and intentional admission unavailability when no
valid local policy exists. Intraday reassignment of an existing instrument requires a separately
designed coordinated Matching migration; ordinary policy publication cannot perform it.

## Considered options

- Embedding assignments in Market Snapshots was rejected because trading rules and Kafka topology
  have different change reasons.
- Keeping Risk's local JSON as the authority was rejected because Risk and Matching would not share
  one versioned policy contract.
- Synchronous Market Reference lookup during admission was rejected because it would add a remote
  dependency to the admission path and its transaction/recovery semantics.
- Hashing or default partitioning on missing policy data was rejected because it could move an
  instrument between Matching routes and break ordering.

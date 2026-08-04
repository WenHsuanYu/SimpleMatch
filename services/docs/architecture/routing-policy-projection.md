# Routing Policy projections

Market Reference publishes a complete versioned `simplematch.routing.v2.RoutingPolicy` protobuf
after its own policy and outbox transaction commits. Risk consumes the serialized event at its
`RoutingPolicyProjectionService` boundary; it does not call Market Reference synchronously and it
does not use the legacy local JSON resolver for this projection.

The Risk consumer validates event metadata, UUID identity, trading day, effective interval,
normalized unique instruments, explicit partition bounds, and the declared `orders.validated`
partition topology. A valid policy is written to Risk-owned parent and assignment tables as
inactive staging state, then activated only after the complete assignment set is durable. A
duplicate identical policy is idempotent; the same policy identity with different content is
rejected. Local lookups return the policy identity and partition together, and unknown instruments
have no hash or default fallback.

New Risk Admissions resolve the applicable projection exactly once at begin time. The pending
journal stores the authoritative policy UUID and explicit partition together in its delivery route
before Account Authority is called; the ingress `routingSnapshotId` remains a separate opaque
field. Terminal outbox creation, equivalent replay, and pending recovery use that persisted route
without consulting the currently active policy again. Rows created before the additive admission
column existed remain readable: recovery preserves their partition and leaves policy identity
absent rather than inventing one.

Risk readiness is out of service until a complete active policy applies to the current
Asia/Taipei trading date and matches the configured partition topology. An expired, future,
missing, invalid, or incomplete local policy never becomes an implicit current policy. The former
Risk-local JSON resolver and hash fallback are retired; no production admission path uses them.

The v1 submission adapter remains source-compatible for controlled migration tests but is not a
Spring production bean because its wire contract lacks venue and authoritative policy identity.
Production ingress is v2 policy-aware Admission. Legacy pending Admissions created during the
additive migration remain readable: recovery uses their persisted partition and leaves the nullable
policy identity absent rather than recomputing or inventing a route.

Market Reference publication enforces continuity for the whole trading date. Its effective
intervals are half-open, ordered, and non-overlapping, so a policy ending at `06:00` may be followed
by one beginning at `06:00` without an ambiguous boundary. A later policy must carry forward every
instrument already assigned that day with the same partition; it may add an instrument that has not
appeared before. The publisher locks the existing day policy history and validates this rule before
inserting either the new active policy or its outbox record. A reassignment, omission, or overlap
therefore leaves both the active policy set and publication outbox unchanged. Taiwan cash-equity
trading is modeled as one continuous session, not morning and afternoon batches.

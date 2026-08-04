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
missing, invalid, or incomplete local policy never becomes an implicit current policy. The legacy
JSON resolver remains migration scaffolding until the consumer-first cutover issue retires it.

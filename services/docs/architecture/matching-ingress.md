# Matching policy-aware ingress

The repository now contains the smallest native Matching boundary needed to
verify Routing Policy provenance. It is a prefactoring seam for the future
Matching Engine, not the Matching Engine itself.

## Boundary

`simplematch_matching_ingress` consumes the shared v2 Protobuf messages from
`proto/`:

- `routing::v2::RoutingPolicy` publishes the policy identity, partition
  topology, and instrument assignments from Market Reference.
- `orders::v2::OrderAdmissionAccepted` carries the policy identity and the
  selected partition from Risk Admission.

The public C++ interface keeps the decision surface small:

1. `stage_routing_policy(payload)` decodes and validates the complete policy
   into an invisible staging view.
2. `activate_staged_routing_policy(routing_policy_id)` publishes the complete
   staged view atomically. `ingest_routing_policy(payload)` remains a
   compatibility convenience that performs both operations.
3. `evaluate_routing_policy_readiness(routing_policy_id, now_unix_ms)` reports
   whether the activated policy is applicable at the supplied instant.
4. `evaluate_accepted_order(payload, consumed_partition)` verifies that the
   order's policy, instrument assignment, declared partition, and consumed
   Kafka partition agree.

Each call returns an explicit `IngressDecision`:

- `kProceed`: the message can continue into a later Matching stage.
- `kPause`: the referenced policy is not projected yet; the partition must
  wait for the policy rather than skip the order.
- `kStop`: the payload or routing provenance is invalid and requires operator
  handling.

The current module deliberately has no order book, matching algorithm,
execution event, Kafka client, or durable policy store. Those concerns belong
to later issues after the contract and recovery semantics are proven.

Policy staging validates metadata, identity, interval, partition topology, and
every normalized instrument assignment before it can affect active state. An
unknown policy pauses its consumed partition; a known-policy instrument or
partition mismatch stops that partition. A restarted native process begins
unprojected and must replay the serialized policy before processing accepted
orders.

The `CriticalOrderIngress` adapter applies the same delivery boundary to accepted
orders: transient failures retry the exact `DeliveryPosition`, exhausted retries
retain event identity and retry history as quarantine evidence, and the affected
partition pauses without rerouting. Restart recovery restores the same position;
known policy or partition mismatches return `kStop` for investigation rather than
silently selecting a different partition.

## Build and test

The root CMake project registers the library and its deterministic test target.
Use the repository's vcpkg preset in a complete development environment:

```bash
cmake --preset vcpkg
cmake --build --preset vcpkg --parallel
ctest --test-dir build-vcpkg --output-on-failure
```

The test target generates the same shared Protobuf sources used by the Java
contract module; it does not introduce a second wire format. The local CMake
fallback also understands a release-only vcpkg installation whose static
Protobuf archive is accompanied by its generated pkg-config dependency graph.

The C++ tests consume the hex-encoded fixtures under
`shared-java/simplematch-contracts/src/test/resources/native-routing-fixtures`.
`NativeRoutingPolicyFixtureCompatibilityTest` proves those bytes are produced
by the generated Java contracts, while the native tests cover staging,
activation, readiness, pause, restart replay, and invariant-stop outcomes.
The critical-delivery tests additionally cover proceed, retry, quarantine, restart,
and same-position recovery.

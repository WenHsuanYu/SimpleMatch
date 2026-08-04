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

1. `ingest_routing_policy(payload)` decodes and validates a policy before
   replacing the in-memory policy view for that identity.
2. `evaluate_accepted_order(payload, consumed_partition)` verifies that the
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

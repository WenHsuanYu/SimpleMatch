# Ordering and Latency

This is the canonical target specification for ordering, fairness, and latency trade-offs in SimpleMatch.

## Deterministic matching boundary

Matching order and fairness are owned by `matching-engine`. Work that can alter matching order must enter one
deterministic, per-instrument pipeline. The engine therefore remains C++ and does not wait on downstream persistence,
market-data publication, audit, or settlement work.

The intended ingress sequence is:

1. `quickfix-gateway` validates session and message shape.
2. `risk-service` accepts or rejects the command through a synchronous, persistence-first gRPC boundary.
3. A validated command is placed on the ordered execution path for its symbol.
4. `matching-engine` produces execution results in that order.
5. All post-match work proceeds asynchronously.

Per-symbol routing or sequencing is required whenever multiple partitions or engine instances are introduced. A routing
snapshot must remain stable for the relevant trading interval so one symbol is not processed by competing ordered loops.

## Latency posture

The target is a millisecond-class system (an indicative P95 below 50 ms), not an assertion of exchange-grade microsecond
latency. The design prioritizes recoverability, observability, and operational evolution while preserving a narrow
deterministic core.

If the product later requires exchange-grade latency and stricter fairness, the pre-match path must be tightened
further: use a per-symbol sequencer, isolate matching CPU and memory resources, and ensure all external I/O occurs after
the matching decision. Kafka and gRPC then remain useful for admission, distribution, replay, and downstream
integration, but are not allowed to insert nondeterministic work into the matching loop.

## Blocking rules

- First successful client acknowledgement waits for durable admission in
  `risk-service`, not for matching completion.
- Matching must not block on PostgreSQL, Kafka publication, market-data delivery, or downstream consumers.
- A local matching journal or WAL may provide the first post-match durability anchor; a background ingester can later
  write durable projections and publish integration events.
- Control-plane updates are versioned and applied outside the order-processing critical section.

These rules let the system distinguish an accepted order from a completed match, preserve an explainable order of
operations, and avoid coupling client latency to the slowest downstream dependency.

# Ordering and Latency

This is the canonical target specification for ordering, fairness, and latency trade-offs in
SimpleMatch.

## Deterministic matching boundary

Matching order and fairness are owned by `matching-engine`. Work that can alter matching order must
enter one deterministic, per-instrument pipeline. The engine therefore remains C++ and does not wait
on downstream persistence, market-data publication, audit, or settlement work.

The intended ingress sequence is:

1. `quickfix-gateway` validates session and message shape.
2. `risk-service` accepts or rejects the command through a synchronous, persistence-first gRPC
   boundary.
3. Risk publishes the validated command to its artifact-assigned `matching.commands` partition.
4. The sole Matching owner for that partition processes the ordered stream and publishes
   deterministic results to the same numeric `matching.events` partition.
5. All post-match work proceeds asynchronously.

The final daily artifact assigns every eligible instrument to exactly one of 15 partitions, with at
most 150 order books per partition. That assignment and the single-owner permit remain stable for
the trading session so an instrument is never processed by competing loops.

## Latency posture

The native engine follows an LMAX Disruptor-style architecture to maximize deterministic
single-core throughput: preallocated SPSC rings surround one single-writer matching core, and the
production pod receives three CPUs with Guaranteed QoS and CPU pinning. The intended core latency
is microsecond-class, but no numeric percentile is promised until the production benchmark fixes
ring capacities, workload, hardware, and acceptance thresholds.

Kafka and gRPC remain outside the matching decision loop. They provide durable admission,
distribution, replay, and downstream integration without introducing network waits, locks, disk I/O,
or dynamic allocation into the hot path.

## Blocking rules

- First successful client acknowledgement waits for durable admission in
  `risk-service`, not for matching completion.
- Matching core must not block on PostgreSQL, Kafka publication, market-data delivery, offset
  commits, Kubernetes, or downstream consumers.
- `matching.commands` is the authoritative input journal. There is no per-command local WAL/fsync
  journal between the matching decision and state mutation.
- The output publisher acknowledges every deterministic result before the input offset becomes
  completed; only a contiguous completed watermark may be committed.
- Artifact, session, schema, identity algorithm, matching algorithm, and partition ownership are
  fixed outside the order-processing critical section.

These rules let the system distinguish an accepted order from a completed match, preserve an
explainable order of operations, and avoid coupling client latency to the slowest downstream
dependency.

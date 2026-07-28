# Troubleshooting

This is the canonical target specification for first-line operational diagnosis. It describes what to inspect, not a
record of past incidents.

## Event delivery and processing

When a consumer appears to apply a result twice, inspect its event identity, idempotency record or unique key, and
offset-commit behavior. When events are late, inspect topic availability, partition routing, consumer lag, outbox or WAL
backlog, and the age of the oldest unpublished event.

## Matching progress

When matching stops advancing, inspect per-symbol routing and fencing, engine health, the validated-order input stream,
and the durable result path. Do not attempt to repair a completed execution by deleting history; use the explicit
correction or compensation flow defined by the architecture.

## Connectivity and readiness

For a failed FIX session, inspect session configuration, the FIX dictionary, network reachability, and gateway logs. For
gRPC failures, inspect the caller's deadline, service DNS resolution, readiness state, and circuit-breaker metrics
before widening retries. For an unready Kubernetes workload, inspect its readiness and liveness diagnostics together
with the dependent service health.

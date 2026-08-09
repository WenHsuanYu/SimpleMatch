# Troubleshooting

This is the canonical target specification for first-line operational diagnosis. It describes what
to inspect, not a record of past incidents.

## Event delivery and processing

When a consumer appears to apply a result twice, inspect its event identity, idempotency record or
unique key, and offset-commit behavior. When events are late, inspect topic availability, partition
routing, consumer lag, outbox or WAL backlog, and the age of the oldest unpublished event.

## Gateway admission and recovery

When QuickFIX Gateway cannot become ready after restart, do not replay commands manually or widen
retries first. Correlate the command WAL, the latest recovery-sidecar state, and Risk's authoritative
`GetAdmissionOutcome(command_id)` result.

- no sidecar state means Risk submission had not started; startup may restore `UNKNOWN` and perform
  the first submission;
- `UNKNOWN` requires reconciliation before a retry decision;
- `PENDING` proves durable Risk ownership and is never retry permission;
- `ACCEPTED` and `REJECTED` are terminal;
- local `PENDING` with authoritative `NOT_FOUND` is an ownership contradiction and should keep the
  Gateway unready for operator investigation.

When the Gateway reports a client-safe system error, use the correlated internal logs to inspect the
actual RPC, deadline, breaker, dependency, or recovery failure. Do not ask clients to resend an
indeterminate order merely because the external message intentionally hides those internal details.
See [Consistency, Recovery, Identity, and Error Boundaries](consistency-recovery-identity-and-errors.md).

## Identity failures

A malformed FIX `Account(1)` is a client input error and should fail before the command enters the
WAL/Risk recovery protocol. For a valid UUID that cannot be resolved operationally, trace the same
canonical value through Gateway, Risk, Account gRPC, Account domain state, and Account persistence;
do not repair the incident by deriving or substituting a different account identifier in Gateway.

## Matching progress

When matching stops advancing, inspect per-symbol routing and fencing, engine health, the
validated-order input stream, and the durable result path. Do not attempt to repair a completed
execution by deleting history; use the explicit correction or compensation flow defined by the
architecture.

## Connectivity and readiness

For a failed FIX session, inspect session configuration, the FIX dictionary, network reachability,
and gateway logs. For gRPC failures, inspect the caller's deadline, service DNS resolution,
readiness state, and circuit-breaker metrics before widening retries. For an unready Kubernetes
workload, inspect its readiness and liveness diagnostics together with the dependent service health.

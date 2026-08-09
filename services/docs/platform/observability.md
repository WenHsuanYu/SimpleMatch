# Observability

This is the canonical target specification for cross-service observability.

## Traces and logs

The target trace follows an order through the gateway, synchronous admission, Kafka, matching, and
downstream consumers. Trace and structured-log attributes must carry the identifiers needed to
reconcile that path, including service, environment, trace identifiers, order identity, command
identity, execution identity, and symbol. FIX-facing logs additionally include session identity and
message sequence information where it is safe to retain.

Operator diagnostics and client-facing error text are different contracts. Internal logs should be
specific enough to identify the failing operation, dependency, reason code/detail, deadline, retry
or breaker state, and relevant correlation identities. External protocol adapters must not copy
those internal details directly into client messages. The authoritative audience and mapping rules
are defined by
[Consistency, Recovery, Identity, and Error Boundaries](consistency-recovery-identity-and-errors.md).

Diagnostic usefulness never overrides secret, credential, privacy, or retention rules. Passwords,
tokens, secret configuration, and prohibited payload data remain excluded even from operator logs.

## Metrics

Minimum metrics cover Kafka consumer lag and producer failures, outbox or WAL backlog and
oldest-event age, matching throughput and latency per shard or symbol, and gRPC success, error,
latency, and breaker state. Gateway recovery monitoring should also make unresolved WAL/sidecar
state and reconciliation failure visible so an unready instance can be diagnosed without blind
retries. Labels should be consistent across services so operators can correlate an incident rather
than join incompatible metric vocabularies.

The shared delivery port exposes stable `simplematch.delivery.events` counters for duplicates,
retries, quarantine, and dead letters, plus `simplematch.delivery.observations` gauges for connector
lag, outbox age, and consumer lag. Component, topic, and partition labels identify the affected
delivery boundary. Services without a registry use the no-op port; they do not change business
delivery semantics when telemetry is unavailable.

## Dashboards and alerts

Dashboards make the ordered execution path, outbox health, matching progress, synchronous dependency
health, and recovery readiness visible together. Alerts should detect growing consumer lag, aging
unpublished events, unresolved recovery state, loss of matching progress, dependency error spikes,
and unhealthy or unready workloads. Thresholds are environment-specific operational configuration,
not fixed in this target specification.

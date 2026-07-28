# Observability

This is the canonical target specification for cross-service observability.

## Traces and logs

The target trace follows an order through the gateway, synchronous admission, Kafka, matching, and downstream consumers.
Trace and structured-log attributes must carry the identifiers needed to reconcile that path, including service,
environment, trace identifiers, order identity, command identity, execution identity, and symbol. FIX-facing logs
additionally include session identity and message sequence information where it is safe to retain.

## Metrics

Minimum metrics cover Kafka consumer lag and producer failures, outbox or WAL backlog and oldest-event age, matching
throughput and latency per shard or symbol, and gRPC success, error, latency, and breaker state. Labels should be
consistent across services so operators can correlate an incident rather than join incompatible metric vocabularies.

## Dashboards and alerts

Dashboards make the ordered execution path, outbox health, matching progress, and synchronous dependency health visible
together. Alerts should detect growing consumer lag, aging unpublished events, loss of matching progress, dependency
error spikes, and unhealthy or unready workloads. Thresholds are environment-specific operational configuration, not
fixed in this target specification.

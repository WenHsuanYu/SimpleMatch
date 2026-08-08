# Deployment

This is the canonical target specification for SimpleMatch deployment topology and service
discovery.

## Local composition

Local development composes the broker and the target services in dependency order: start Kafka
first, then risk and matching, then persistence and market-data publication, then the FIX gateway
and streaming service. Local composition is a topology check, not a substitute for service-level
certification.

Kafka Connect and Debezium are optional local dependencies when validating the outbox publication
path. Their configuration belongs with deployment assets; the reliability guarantee they support is
specified in the architecture area.

The local CDC contract check is
`bash scripts/run-outbox-cdc-contract-check.sh`. It starts isolated PostgreSQL, Kafka, and Kafka
Connect containers, applies one connector per owning outbox schema, and verifies binary payload
bytes, keys, headers, timestamps, explicit partitions, and outbox retention across pause/resume.
Set `SIMPLEMATCH_POSTGRES_PORT` when the host's default PostgreSQL port is occupied.

## Kubernetes target

Kubernetes is the default target deployment environment. Each service exposes readiness and liveness
behavior so traffic reaches only ready instances. The matching engine's shard ownership is
controlled by explicit routing and fencing rules; service discovery must not decide a shard owner
implicitly.

## Service discovery

Use Kubernetes Service DNS as the baseline discovery mechanism when services run inside Kubernetes.
It supplies stable naming, endpoint membership based on readiness, and basic load distribution. gRPC
clients must reconnect or re-resolve endpoints after connection failure or rollout events.

Introduce Consul or a service mesh only when a concrete cross-platform, cross-cluster, or
policy-governance need exceeds Kubernetes Service DNS. Those tools are not a default dependency of
the target architecture.

Each connector is scoped to one service-owned outbox table. A future service receives an outbox
connector only after it owns authoritative state and durable outbound events; merely consuming an
existing topic does not justify a connector. Connector lag, source outbox age, consumer lag,
duplicates, retries, quarantine, and dead-letter counts are operational metrics rather than domain
state.

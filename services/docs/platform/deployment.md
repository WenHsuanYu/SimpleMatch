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

### Fixed Matching fleet

The Matching deployment is a fifteen-replica StatefulSet. Its ordinal `N`, read from the
StatefulSet pod-index label, is the only allowed owner of `matching.commands` and `matching.events`
partition `N`. The `matching-partition-00` through `matching-partition-14` Lease objects are
pre-created and renewed by their matching runtime. A Kubernetes adapter turns the observed Lease
holder identity, partition, and trading session into a native `PartitionOwnershipPermit`; no
Kubernetes type enters the single-writer core.

The permit starts denied. It permits direct Kafka assignment, replay, matching, publication, and
readiness only for the matching ordinal's current Lease holder. A missed renewal becomes uncertain
immediately and self-fences after five seconds. The replacement waits for PVC attachment and Lease
ownership, then replays from the Open Barrier before becoming Ready. The normal recovery procedure
prohibits force deletion; see [Matching fleet recovery](matching-fleet-recovery.md).

Each ordinal owns a `ReadWriteOncePod` PVC that contains bounded baseline metadata only. The
StorageClass must use a compatible CSI driver. Production pods request and limit exactly three CPUs
and equal memory request/limit values for Guaranteed QoS, and schedule only on nodes certified for
CPU Manager static policy. The PodDisruptionBudget permits at most one unavailable Matching pod.

The reviewed daily artifact normally mounts from an immutable ConfigMap. If its final canonical JSON
exceeds 900 KiB, a digest-pinned OCI data image populates the same mount path through an init
container. Both paths give Risk and Matching exact approved artifact bytes; neither changes while
the market is open.

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

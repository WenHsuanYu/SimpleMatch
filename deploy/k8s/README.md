# Kubernetes Configuration

Spring services import `simplematch-platform-config`, their service ConfigMap, and their service
Secret through Spring Cloud Kubernetes Config Data. Apply the ConfigMaps before starting a workload.
Provision each `{service}-secrets`
Secret outside Git and grant its service account only `get` access to named ConfigMaps and Secrets.

For `staging` and `production`, the Secret must contain
`simplematch.postgres.dsn`; no ConfigMap may define that key. The quickfix-gateway StatefulSet is
the reference deployment: it enables the non-optional Kubernetes Config Data import, selects
`production`, and uses the narrowly scoped RBAC manifest.

The staging/production overlays include a retained Debezium Kafka Connect worker. The worker's
connector ConfigMaps contain only non-sensitive connector settings; the worker itself receives
PostgreSQL endpoints from `simplematch-kafka-connect-config` and all connector credentials from the
external `simplematch-kafka-connect-secrets` Secret. After a configuration change, roll the worker
and re-apply the connector definitions. Configuration reload is intentionally disabled.

## Cross-service base and overlays

`base/` contains the Java service Deployments for Account, Risk, Persistence, Market Data
Projection, Marketdata Publisher, Marketdata Streamer, and Query Service, plus the existing
QuickFIX/Matching resources, service-local ConfigMaps, read-only configuration RBAC, migration Jobs,
probes, and NetworkPolicy.
The four overlays are `local`, `test`, `staging`, and `production`.

## Environment separation

`local` is the executable repository-owned environment. It uses locally built images with the
`local` tag and is the deployment surface used by the local production-like certification gate.
The local image set currently includes Account, Risk, Persistence, Market Data Projection,
Marketdata Publisher, Marketdata Streamer, Query Service, Flyway Runner, Matching, and QuickFIX
Gateway. PostgreSQL, Redis, and Kafka are separate Kubernetes workloads in the local overlay; they
are not reached through the retired Compose bridge.

`staging` and `production` are promotion templates, not local verification environments. They use
separate registry names and digest placeholders, and retain placeholders for external PostgreSQL,
Kafka, Redis, OpenTelemetry, CIDR, and Secret values. Filling those values and publishing images is
outside the current local completion boundary.

### Canonical local kind cluster

The repository-managed local resilience lab is the reusable `simplematch-live` kind cluster. It has
one tainted control plane and three labeled workers with stable local-resilience slots. Create and
verify it explicitly before a resilience run:

```text
bash scripts/manage-simplematch-live.sh create
bash scripts/manage-simplematch-live.sh verify
```

`create` refuses to modify an existing cluster. `verify` checks the topology, worker labels, kind
container mapping, StorageClass, and a disposable PVC/Pod probe that confirms the provisioned PV
contains node affinity. `delete` is reserved for an explicit rebuild or cutover operation and
verifies the canonical cluster identity before deleting it:

```text
bash scripts/manage-simplematch-live.sh delete
```

Normal local resilience cleanup never deletes this reusable cluster. It deletes only the generated
run namespace and resources owned by that run.

The runner has two profiles:

```text
bash scripts/run-local-resilience.sh --profile contract
bash scripts/run-local-resilience.sh --profile full-local
```

The `contract` profile is static and cannot produce runtime resilience evidence. The `full-local`
profile owns one run namespace, evidence directory, bounded verdicts, and cleanup; scenarios that
are not yet executable remain explicitly incomplete.

### Local production-like version contract

The executable local profile is checked against this stable version set as of 2026-08-12. The
versions are explicit rather than `latest`; update them together with the upstream compatibility
review and the local contract test.

| Component | Version | Repository source |
| --- | --- | --- |
| Gradle wrapper | 9.7.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Spring Boot | 4.1.0 | `gradle/libs.versions.toml` |
| vcpkg | 2026.07.29 | `ci-native.yml`, `Dockerfile.matching` |
| Apache Kafka | 4.3.1 | `kafka-kraft.yaml` and local Compose profile |
| PostgreSQL | 18.4 | `postgresql.yaml` and local Compose/Flyway CI |
| Redis | 8.8.1-alpine | `redis.yaml` and local Compose profile |
| Debezium Kafka Connect | 3.6.0.Final | local Compose profile |
| Matching build base | Ubuntu 26.04 LTS | `Dockerfile.matching` |

The local Kustomize patch intentionally removes physical-node anti-affinity and lowers Matching
resource requests so fifteen logical owners can run on a disposable kind node. The base, staging,
and production manifests retain the strict three-CPU, fifteen-node production contract.

Render and validate them with:

```text
bash scripts/test-kubernetes-overlays.sh
bash scripts/test-local-kubernetes-dependencies.sh
```

The local dependency contract is deliberately small. PostgreSQL is a node-local singleton on worker
slot 0 with one RWO PVC and a protective PDB; its worker loss is fail-closed until the required
storage returns. Redis is a portable disposable cache with no PDB and a 30-second portable-workload
toleration. Kafka is a fixed three-member KRaft StatefulSet with one broker/controller per worker,
RF3/minimum ISR 2 topic durability, and a two-available PDB. These are local lab contracts, not
cross-node storage HA or production certification.

The base deliberately reuses the reviewed flat Matching and QuickFIX manifests. The renderer uses
`--load-restrictor LoadRestrictionsNone` for those repository-local files; it does not permit
arbitrary paths outside this repository. Staging and production replace every application and
migration image with a digest-pinned reference, require SASL/TLS for Kafka, require mTLS for the
Account/Risk gRPC pair, and add explicit external endpoint NetworkPolicy entries. The
`registry.example.invalid` image names and `203.0.113.0/24` documentation CIDRs are release
placeholders and must be replaced during environment promotion.

### External Secret contract

Secrets are provisioned outside Git. ConfigMaps contain endpoint names, topic names, pool policy,
and certificate paths only; they never contain a DSN, password, SASL value, or private key.

Each service Secret named `{service}-secrets` supplies `postgres_dsn` for its owner schema. Staging
and production values must use PostgreSQL TLS, for example a JDBC DSN with
`sslmode=verify-full` and `sslrootcert=/etc/simplematch/postgres-tls/ca.crt`. The secure overlay
mounts `simplematch-postgres-tls` with a required `ca.crt` at that path, so a missing CA fails pod
startup. The canonical `simplematch.postgres.dsn` property is supplied through
`SIMPLEMATCH_POSTGRES_DSN`; no `spring.datasource.*` key is used.

`account-service-tls`, `risk-service-tls`, and `marketdata-streamer-tls` contain `tls.crt`,
`tls.key`, and `ca.crt`. The
staging/production overlay enables mTLS and requires all three paths. `simplematch-kafka-tls`
contains `ca.p12`; `simplematch-kafka-secrets` contains `sasl_jaas_config` and
`truststore_password`. `risk-service-secrets` additionally supplies `trading_day` and
`matching_image_digest`; `query-service-secrets` supplies `trading_day`.

`quickfix-gateway-http-tls` and `market-data-projection-http-tls` contain `tls.crt` and `tls.key`.
The secure overlay enables HTTPS for the authenticated operator endpoints and changes their
Kubernetes probes to HTTPS; the operator token remains required at the application boundary.
`simplematch-gateway-operations-secrets` supplies `operator_token` to the Gateway, while
`market-data-projection-secrets` supplies `rebuild_operator_token` to the projection reset
endpoint.

`simplematch-kafka-connect-secrets` supplies the three connector user/password pairs plus
`kafka_sasl_jaas_config` and `kafka_truststore_password`, and is required by the retained Debezium
worker. `simplematch-flyway-secrets` supplies the TLS-enabled
`postgres_dsn` consumed by the external
`simplematch/flyway-runner` image. Each Job passes one service ID and schema to that runner, so
Flyway history remains service-local. Jobs are intentionally one-shot: delete and recreate the
named Job for a later migration release, and apply migrations before rolling the Deployments.

The service accounts can read only their named ConfigMaps and service Secret through the included
Roles. NetworkPolicy permits same-namespace service traffic and DNS by default; staging and
production must replace the external IP placeholders with the approved PostgreSQL, Kafka, Redis,
and OpenTelemetry endpoint ranges before apply. The Deployment environment carries stable OTEL
service/resource identity; collector/agent installation remains an environment-owned prerequisite.

## Fixed Matching fleet

`matching-statefulset.yaml` defines the Phase 1 fleet: fifteen StatefulSet ordinals map directly to
Kafka partitions `0` through `14`. The workload obtains the ordinal from the StatefulSet
`apps.kubernetes.io/pod-index` label, so the production cluster must support that label. The native
runtime derives `matching-partition-%02d` from that ordinal and will process only after its own Lease
observation produces a valid `PartitionOwnershipPermit`.

Apply `matching-headless-service.yaml`, `matching-lease-rbac.yaml`, and
`matching-partition-leases.yaml` before the StatefulSet. The Role intentionally has no `create`
verb: all fifteen Lease objects are pre-created, and a pod may only get, patch, or update their known
names. A holder identity contains the Pod UID, partition, and trading session. The workload renews
every two seconds, treats a renewal as uncertain immediately, and self-fences after five seconds of
unconfirmed renewal. A replacement waits for the old Lease to expire, acquires it, replays, and then
passes readiness; it never takes another ordinal's partition.

Each ordinal receives its own `matching-baseline` PVC using `ReadWriteOncePod`. The configured
`simplematch-rwo-pod` StorageClass must be backed by a compatible CSI driver. The baseline holds
only recovery coordinates; Kafka remains the authoritative command journal. The workload requests
and limits three CPUs and the same memory value so it receives Guaranteed QoS. Nodes must carry the
`simplematch.io/cpu-manager-static=true` label only after CPU Manager static-policy certification.

The standard artifact source is the reviewed immutable `matching-daily-artifact` ConfigMap, whose
`market_reference.json` and external `market_reference.sha256` are mounted at
`/etc/simplematch/market-reference/market_reference.json` and
`/etc/simplematch/market-reference/market_reference.sha256`. Create the immutable session ConfigMap
from `matching-session-config.example.yaml` only after replacing its trading-day, session-ID, and
Matching image-digest placeholders. The daily artifact ConfigMap itself is generated from approved
canonical bytes and is never changed during an open session. Risk and Matching must use the same
artifact checksum, trading day, and image digest before the Gateway can open.

When the final artifact exceeds 900 KiB, use the reviewed
`matching-artifact-oci-data-image-patch.json` in the deployment renderer instead. It replaces the
ConfigMap volume with an `emptyDir` populated by a digest-pinned data-image init container, while
preserving the same runtime artifact path. Replace the placeholder digests in both manifests with
approved image digests before deployment.

`bash scripts/test-matching-kubernetes-manifests.sh` verifies the structural deployment contract
without requiring a live cluster. The normal recovery procedure is in
[Matching fleet recovery](../../services/docs/platform/matching-fleet-recovery.md).

The strict live gate is bash scripts/verify-matching-fleet-live.sh. It requires all 15 pods to be
Ready with real digest-pinned images, current per-ordinal Lease holders, Bound
ReadWriteOncePod PVCs, and 15 distinct nodes. The complete Kafka, PostgreSQL, and external
QuickFIX sequence is recorded in
[Production Live Certification](../../docs/production-live-certification.md).

The local production-like gate is
`bash scripts/run-local-production-like-certification.sh`. It verifies the same logical 15-owner,
Lease, PVC, Kafka, and restart/replay contracts with local images and disposable infrastructure;
it does not require 15 physical nodes or real registry digests. The gate applies the approved
immutable Market Reference under the local `matching-daily-artifact` name, creates the platform
resources, runs Flyway Jobs before creating runtime workloads, and then verifies the Java,
QuickFIX, and Matching rollouts. Risk and Query receive their local session identity from
`matching-session-config`; the superseded `marketdata-publisher` runtime is disabled only in the
local overlay. The investigation and troubleshooting record is in
[Local production-like Kubernetes workload startup](../../docs/local-production-like-kubernetes-workload-startup.md).

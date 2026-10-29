# Kubernetes Configuration

Spring services import `simplematch-platform-config`, their service ConfigMap, and their service
Secret through Spring Cloud Kubernetes Config Data. Apply the ConfigMaps before starting a workload.
Provision each `{service}-secrets`
Secret outside Git and grant its service account only `get` access to named ConfigMaps and Secrets.

For `staging` and `production`, each service Secret must contain the `postgres_dsn` key; no
ConfigMap may define the canonical `simplematch.postgres.dsn` property. The service's
`SIMPLEMATCH_POSTGRES_DSN` reference maps that Secret key to the canonical property. The
quickfix-gateway StatefulSet is
the reference deployment: it enables the non-optional Kubernetes Config Data import and uses the
narrowly scoped RBAC manifest. The base manifest defaults to the `local` profile; the `test`,
`staging`, and `production` overlays own their explicit Spring profile selection.

The staging/production overlays include a retained Debezium Kafka Connect worker. The worker's
connector ConfigMaps contain only non-sensitive connector settings; the worker itself receives
PostgreSQL endpoints from `simplematch-kafka-connect-config` and all connector credentials from the
external `simplematch-kafka-connect-secrets` Secret. After a configuration change, roll the worker
and re-apply the connector definitions. Configuration reload is intentionally disabled.

## Cross-service base and overlays

`base/` contains the Java service Deployments for Account, Risk, Persistence, Market Data
Projection, Marketdata Streamer, and Query Service, plus the existing
QuickFIX/Matching resources, service-local ConfigMaps, read-only configuration RBAC, migration Jobs,
probes, and NetworkPolicy.
The four overlays are `local`, `test`, `staging`, and `production`.

## Environment separation

`local` is the executable repository-owned environment. It uses locally built images with the
`local` tag and is the deployment surface used by the local production-like certification gate.
The local image set currently includes Account, Risk, Persistence, Market Data Projection,
Marketdata Streamer, Query Service, Flyway Runner, Matching, and QuickFIX
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

Static deployment contracts can be checked with:

```text
bash scripts/validate-local-resilience-contract.sh
```

The validator checks rendered deployment policy. Its result cannot establish runtime recovery.
Use `bash scripts/test-phase1-deployment-contracts.sh` for the shared local and CI regression collection.
The project retired the extra static collection and its custom result envelope.

Runtime recovery uses focused diagnostics for a specific property.
The project retired the former `full-local` workload-by-fault scenario matrix.
A focused report supports only the recovery property that it observes.
It cannot establish broader resilience or production HA.

### Local host memory budget

All three kind workers share the Docker daemon's host memory. The repository's reference local
budget is 38 GiB, recorded in `overlays/local/resource-budget.json`; the Docker Desktop daemon
used to set this reference reported about 38.26 GiB on 2026-10-06. The production-like runner
reads the *selected daemon's current* memory capacity and runs the fresh `local-resource-budget`
phase before image builds or Kubernetes apply. Its report is retained at
`local-resource-budget.json` in the run evidence directory and the final report links its verdict.
If declared requests exceed that capacity, the runner warns but still permits the runtime attempt.
This comparison is an early capacity signal, not a scheduling guarantee or a measurement of live
memory, swap use, per-worker placement, or available space during a rollout.

The full local overlay currently renders 41.7421875 GiB of steady requests and 6.125 GiB from
its seven Jobs. The actual bootstrap stage runs platform services alongside Jobs (10.25 GiB of
requests), then starts the full steady set after Jobs complete; its declared stage peak is thus
41.7421875 GiB, above the 38 GiB reference. The 47.8671875 GiB all-together sum is only a
conservative review envelope, not the startup gate. The existing `--matching-fleet-only` runner
profile fits this reference by declared requests: it applies PostgreSQL, Redis, three Kafka brokers, the
topic-provisioning Job, and all 15 Matching owners. Its exact workload selection is recorded in
`overlays/local/resource-budget.json`; it renders 34.2421875 GiB steady, 4.25 GiB during
bootstrap, and a 34.2421875 GiB declared stage peak. This is a `PARTIAL` Matching fleet gate.
The full profile may still run here: actual memory can be below requests and swap may help, as
earlier runtime attempts suggest, but neither a prior run nor swap certifies today's request fit
or guarantees success. A full certification verdict still requires its own live evidence.

The Matching main container retains the observed, successful 2 GiB local fleet limit and its
bounded 1,024-order and 524,288-slot configuration. Fifteen main containers therefore request
30 GiB; the status sidecars add 120 MiB. The native direct-core RSS benchmark is a different
workload from a full Kafka-connected pod and does not justify lowering the pod to 1 GiB. Kafka's
three 1 GiB broker requests, PostgreSQL's 1 GiB request, and Redis's 128 MiB request complete the
reduced steady set. The Kafka storage-format init container now declares a bounded request and
limit; its 1 GiB memory request does not add to each pod's effective request because that pod's
broker already requests 1 GiB. These are local sizing choices; the base and promotion templates
retain their separate production resource contract.

Run the focused render/resource check with `ruby scripts/test-local-resource-budget.rb`. It
re-renders the local overlay, compares each profile's review lines with checked-in text baselines using
`diff`, and verifies that the reduced profile fits the 38 GiB reference while strict `--check`
rejects an over-budget fixture. Without `--check`, the calculator warns and records excess but
exits successfully so the runner can continue. To inspect current numbers directly, pipe `kubectl kustomize
deploy/k8s/overlays/local --load-restrictor LoadRestrictionsNone` into `ruby
scripts/local-resource-budget.rb --manifest - --profile full` (or
`--profile matching-fleet-only`). The JSON separates bootstrap, steady, actual stage peak, and
the deliberately conservative all-together envelope; none is an observed RSS peak.

### Local production-like version contract

The executable local profile uses the explicit versions below. The baseline was reviewed on
2026-08-12; the Redis pin was updated on 2026-10-28 to match the
[Redis 8.10.3 release](https://github.com/redis/redis/releases/tag/8.10.3) and its
[official Alpine image](https://github.com/docker-library/official-images/blob/master/library/redis).
Update version pins together with the upstream compatibility review and the local contract test.

| Component | Version | Repository source |
| --- | --- | --- |
| Gradle wrapper | 9.7.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Spring Boot | 4.1.0 | `gradle/libs.versions.toml` |
| vcpkg | 2026.07.29 | `ci-native.yml`, `Dockerfile.matching` |
| Apache Kafka | 4.3.1 | `kafka-kraft.yaml` and local Compose profile |
| PostgreSQL | 18.4 | `postgresql.yaml` and local Compose/Flyway CI |
| Redis | 8.10.3-alpine | `redis.yaml` and local Compose profile |
| Debezium Kafka Connect | 3.6.0.Final | `debezium-kafka-connect-local.yaml` and local Compose profile |
| Matching build base | Ubuntu 26.04 LTS | `Dockerfile.matching` |

The local Kustomize patch intentionally removes physical-node anti-affinity and lowers Matching
resource requests so fifteen logical owners can run on a disposable kind node. The base, staging,
and production manifests retain the strict three-CPU, fifteen-node production contract.

The five replicated Java workloads in the local resilience overlay use the same placement contract:
two replicas on the `local-resilience` worker pool, hostname spreading with `maxSkew: 1` and
`DoNotSchedule`, a `minAvailable: 1` PDB, and explicit 30-second `NoExecute` tolerations for the
portable-workload, `not-ready`, and `unreachable` taints. The shared contract validator checks these
fields after Kustomize rendering so a patch that only looks correct in source cannot pass by itself.

The local overlay keeps QuickFIX as one owner on worker slot 1, with its owner-specific Service,
`minAvailable: 1` PDB, and node-local RWO PVC. `marketdata-streamer` remains one `Recreate` owner,
but selects the whole local-resilience pool and tolerates the portable-workload taint so a replacement
can use another worker. The rendered validator checks only these ownership and placement signals;
same-owner FIX recovery is tracked separately in #164. Streamer worker-loss certification is no
longer a project completion gate; the static ownership contract remains valid without a second
manifest mirror.

Render and validate them with:

```text
bash scripts/test-kubernetes-overlays.sh
bash scripts/test-local-kubernetes-dependencies.sh
```

The local dependency contract is deliberately small. PostgreSQL is a node-local singleton on worker
slot 0 with one RWO PVC and a protective PDB; its worker loss is fail-closed until the required
storage returns. Redis is a portable disposable cache with no PDB and a 30-second portable-workload
toleration plus explicit 30-second tolerations for Kubernetes' `not-ready` and `unreachable`
`NoExecute` taints. Kafka is a fixed three-member KRaft StatefulSet with one broker/controller per worker,
RF3/minimum ISR 2 topic durability, and a two-available PDB. These are local lab contracts, not
cross-node storage HA or production certification.

The dependency lifecycle seam is available without rerunning the full local runner:
`scripts/run-local-resilience-dependencies.sh --component postgresql|redis|kafka --namespace NAME --namespace-run-id ID`

The dependency diagnostic prepares its Java report verifier before it creates markers or injects a fault.
Use the project's normal JDK and Gradle environment. Current reports use schema version 2.
See the [verifier instructions](../../tools/recovery-evidence-verifier/README.md) for acceptance rules and report scope.

The diagnostic consumes an existing lifecycle-labelled disposable namespace only after proving the exact run-id,
captures exact Pod/Node/PVC/PV and data identity, injects one bounded worker-stop (or Pod restart),
and writes a diagnostic-only report. Before fault injection it also requires a bounded stable window
with Ready `etcd`, `kube-controller-manager`, and `kube-scheduler`, unchanged restart counts,
and no recent control-plane lease/probe/failure event.
PostgreSQL must return with its original node-local PVC and the Flyway-owned
`risk_service.local_resilience_marker` row; this diagnostic marker is separate from the observer-owned
`risk_service.cdc_delivery_lag` health row. Kafka must retain its RF3 marker, two-broker availability
during the fault, and all three ISR after rejoin; during a PostgreSQL worker-stop, the diagnostic also
checks that no replacement Pod is assigned to another worker before the original worker returns.
Redis is expected
to be rebuildable because its `emptyDir` state is disposable. Namespace, worker-container, cluster
identity, or data mismatches fail closed. PostgreSQL's diagnostic marker is deleted after the durable
row observation is captured; cleanup failure is itself a failed diagnostic. A Redis worker-stop report
waits for the node-controller
taint path and allows up to 150 seconds for a new Ready Pod on a different worker; this focused report supports only the Redis recovery behavior it actually observes; it does not
establish a broader resilience, production-HA, or cross-node storage claim.

The local overlay also runs two Debezium Kafka Connect workers against the in-cluster Kafka and
PostgreSQL Services. The certification phase applies this Connect Deployment only after the Flyway
Jobs and Kafka topic-provisioning Job complete; a later phase applies the Java workloads, then
registers each retained service-owned connector (Risk and Account) through the Connect REST API and
records its `RUNNING` connector/task status before waiting for application workloads. The focused
`scripts/run-local-connect-worker-loss.sh` diagnostic can delete the task-owning worker Pod and
requires a task-id-preserving reassignment plus baseline-aware Account CDC evidence; it never
re-applies the deployment or deletes the cluster.
Its PASS report links prerequisite snapshots, the UID delete precondition,
the transition record, and the exact Kafka publication location; report
booleans are not accepted without those artifacts.
Risk additionally runs a dedicated Kafka observer group: it persists exact Debezium `id` headers,
proves committed offsets reached every current `matching.commands` partition head, and only then
refreshes the durable admission-lag row. The full Kubernetes gate pauses the Risk connector,
proves a pending outbox row raises the durable lag and Actuator gauges, resumes the connector, and
checks exact observation plus zero-lag recovery. This is a local plaintext/Secret-backed lab
profile; staging and production keep the separate TLS/SASL template.

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
`SIMPLEMATCH_POSTGRES_DSN`; startup requires that effective property to exactly match the Secret's
`postgres_dsn` value, and no `spring.datasource.*` key is used.

`account-service-tls`, `risk-service-tls`, `marketdata-streamer-tls`, and `quickfix-gateway-tls`
contain `tls.crt`, `tls.key`, and `ca.crt`. The staging/production overlay enables mTLS and
requires all three paths. `simplematch-kafka-tls`
contains `ca.p12`; `simplematch-kafka-secrets` contains `sasl_jaas_config` and
`truststore_password`. `risk-service-secrets` additionally supplies `trading_day` and
`matching_image_digest`; `query-service-secrets` supplies `trading_day`.

`quickfix-gateway-http-tls` and `market-data-projection-http-tls` contain `tls.crt` and `tls.key`.
The secure overlay enables HTTPS for the authenticated operator endpoints and changes their
Kubernetes probes to HTTPS; the operator token remains required at the application boundary.
`simplematch-gateway-operations-secrets` supplies `operator_token` to the Gateway, while
`market-data-projection-secrets` supplies `rebuild_operator_token` to the projection reset
endpoint.

`simplematch-kafka-connect-secrets` supplies the two retained connector user/password pairs plus
`kafka_sasl_jaas_config` and `kafka_truststore_password`, and is required by the retained Debezium
worker. `simplematch-flyway-secrets` supplies the TLS-enabled
`postgres_dsn` consumed by the external
`simplematch/flyway-runner` image. Staging and production mount the required
`simplematch-postgres-tls` CA Secret into every Flyway Job at
`/etc/simplematch/postgres-tls`; the DSN's `sslrootcert` must point to its `ca.crt`. Each Job passes
one service ID and schema to that runner, so Flyway history remains service-local. Jobs are
intentionally one-shot: delete and recreate the named Job for a later migration release, and apply
migrations before rolling the Deployments.

The service accounts can read only their named ConfigMaps and service Secret through the included
Roles. NetworkPolicy permits same-namespace service traffic and DNS by default; staging and
production must replace the external IP placeholders with the approved PostgreSQL, Kafka, Redis,
and OpenTelemetry endpoint ranges before apply. The Deployment environment carries stable OTEL
service/resource identity; collector/agent installation remains an environment-owned prerequisite.

### Query Redis transport and ACL contract

Managed Query caching requires `redis_username` and `redis_password` in `query-service-secrets`
and a `ca.crt` PEM CA bundle in the required `query-service-redis-tls` Secret. The client verifies
the certificate chain and the configured DNS hostname; there is no insecure verification mode.
Query refuses an enabled plaintext cache in staging/production and rejects missing credentials,
the Redis `default` identity, and credentials on plaintext connections. Local/test lab settings and
the market-data projection's existing shared Redis endpoint are independent of these Query inputs.

The managed templates select `query-redis.{environment}.example.invalid:6380` and a Query-only
`203.0.113.14/32` egress placeholder. Replace both with the approved TLS endpoint and its restricted
network range before promotion. Configure the server's plaintext port off and default user off;
provision a dedicated Query ACL identity with only this namespace and command allowlist:

```text
user query-cache on #<externally-provisioned-password-sha256> ~query:v2:tls:* -@all +hello +ping +client|setinfo +get +set +del +scan
```

Apply that policy through the provider's ACL mechanism and deliver the matching password through
the Secret; do not commit credentials. `HELLO`, `PING`, and `CLIENT SETINFO` support the Lettuce
handshake. Administrative commands, `KEYS`, other databases, and another service's values must be
denied. Clearing uses `SCAN MATCH query:v2:tls:* COUNT 100` and at most 100 keys per `DEL`, checking
every returned key against the literal namespace. Redis ACL key patterns do **not** restrict
`SCAN`'s enumeration of key names: use a dedicated Query cache instance with no other service's
keys, rather than claiming that the key ACL alone hides shared-instance metadata.

Roll out in stages: provision the TLS-only endpoint, ACL, and Secrets first; then roll Query with
the new `query:v2:tls` prefix. It starts cold and repopulates from its own PostgreSQL projections,
so old plaintext `query:v1` entries are never trusted by new managed replicas. Keep PostgreSQL
fallback available throughout; after old replicas drain, allow their 30-second entries to expire
and retire their endpoint access. Do not dual-read the old unprotected cache. Credential rotation
may overlap old/new provider-side passwords until the Secret rollout completes, then revoke the
old password. External ACL enforcement and the promoted endpoint remain operator-owned checks.

Run `./gradlew -q :services:query-service:redisSecurityTest` with Docker, OpenSSL, and the repository JDK/Gradle dependencies available.
JUnit prepares private certificate and ACL files in a temporary directory. Testcontainers starts three disposable
`redis:8.10.3-alpine` containers, selects their mapped ports, and waits for startup.
JUnit closes every container after the suite, including setup failures. It then removes the temporary files.
Each OpenSSL operation has a 30-second deadline. Fixture errors fail before container startup.
The six cases check trusted cache operations, certificate rejection, authentication failures, ACL restrictions, and cache namespace isolation.
Java CI runs this gate explicitly. Docker or fixture failures fail the gate instead of skipping it.

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
[Production Live Certification](../../docs/verification/local-production-like-certification.md).

The local production-like gate is
`bash scripts/run-local-production-like-certification.sh`. It verifies the same logical 15-owner,
Lease, PVC, Kafka, and restart/replay contracts with local images and disposable infrastructure;
it does not require 15 physical nodes or real registry digests. The gate applies the approved
immutable Market Reference under the local `matching-daily-artifact` name, creates the platform
resources, runs Flyway Jobs before registering the retained Risk and Account outbox connectors and
creating runtime workloads, and then verifies the Java, QuickFIX, and Matching rollouts. Risk and
Query receive their local session identity from `matching-session-config`. The investigation and
troubleshooting record is in
[Historical local production-like Kubernetes workload startup investigation](../../docs/archive/local-production-like-kubernetes-workload-startup.md).

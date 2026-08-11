# Kubernetes Configuration

Spring services import `simplematch-platform-config`, their service ConfigMap, and their service
Secret through Spring Cloud Kubernetes Config Data. Apply the ConfigMaps before starting a workload.
Provision each `{service}-secrets`
Secret outside Git and grant its service account only `get` access to named ConfigMaps and Secrets.

For `staging` and `production`, the Secret must contain
`simplematch.postgres.dsn`; no ConfigMap may define that key. The quickfix-gateway StatefulSet is
the reference deployment: it enables the non-optional Kubernetes Config Data import, selects
`production`, and uses the narrowly scoped RBAC manifest.

The Debezium connector ConfigMap contains only non-sensitive connector settings. Inject
`RISK_SERVICE_POSTGRES_USER` and
`RISK_SERVICE_POSTGRES_PASSWORD` into the Kafka Connect worker from a Secret. After a configuration
change, roll the relevant workload. Configuration reload is intentionally disabled.

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

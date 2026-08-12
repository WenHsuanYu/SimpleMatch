# Kind worker loss: storage and recovery decision

## Resolution for #144

`local-path` is node-local storage, not a replicated-storage or failover mechanism. A
local PV records the node on which its path exists through PV `nodeAffinity`; Kubernetes
uses that constraint for scheduling, but does not copy the bytes to another node or
rewrite the affinity when a worker disappears. The local-path provisioner documents both
the per-node path map and the node-affinity key it writes to provisioned PVs
([local-path configuration](https://github.com/rancher/local-path-provisioner#configuration),
[local-path node-affinity configuration](https://github.com/rancher/local-path-provisioner#node-affinity-key)).

Therefore:

- Draining a worker removes evictable Pods, so their `emptyDir` data is lost, but it does
  not by itself delete a PVC or migrate a local PV. A replacement StatefulSet Pod can
  remain Pending while its PVC is still constrained to the drained worker.
- Stopping and restarting the same kind worker may make the same node-local bytes
  visible again. That is a same-node/container-restart observation, not evidence of
  cross-node durability.
- Permanently removing the worker removes the node-local path unless it was deliberately
  backed by an external/host-mounted persistence arrangement. The PV/PVC API objects may
  remain, subject to their lifecycle and reclaim policy, while the volume is inaccessible
  and the Pod cannot be scheduled elsewhere.
- A StatefulSet preserves ordinal identity and PVC association; it does not replicate
  application data. Kafka replication, PostgreSQL replication, and an active/standby
  QuickFIX design remain separate application or storage designs.

This resolves the research question without certifying a worker-loss experiment that has
not run. The local resilience lab can prove control-plane and application behavior only
when it explicitly provisions a multi-worker kind cluster and executes each failure
action. The checked-in lab record currently proves a disposable kind Matching smoke with
Lease/PVC/replay and normal Pod recreation, and labels the one-node/RF1 profile
non-certifying; it does not claim kind worker drain, stop, or permanent removal
resilience ([local production-like certification record](../production-live-certification.md)).

## What each worker action means

Kind runs Kubernetes nodes as Docker containers ([kind quick start](https://kind.sigs.k8s.io/docs/user/quick-start/),
[kind design principles](https://kind.sigs.k8s.io/docs/design/principles/)). Thus “stop”,
“restart”, and “remove” below are controlled Docker/node-container lab actions, not a
kind storage-failover API. A multi-node configuration is useful for testing rolling
behavior, but kind documents that its workers are containers with limited isolation
([kind configuration](https://kind.sigs.k8s.io/docs/user/configuration/)).

| Action | Kubernetes behavior | Local PV / `emptyDir` consequence | Honest assertion |
| --- | --- | --- | --- |
| Drain | `kubectl drain` marks the node unschedulable and evicts/deletes eligible Pods. `--delete-emptydir-data` explicitly permits deletion of Pods whose `emptyDir` data will be lost ([`kubectl drain`](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_drain/)). | A PVC/PV is not the same thing as `emptyDir`; the claim and PV normally remain. A local PV remains tied to its node through `nodeAffinity` ([local volumes](https://kubernetes.io/docs/concepts/storage/volumes/#local)). | The lab can prove eviction, rescheduling, PV affinity, and the loss of Pod-scoped scratch data. It cannot call a successful same-node reschedule a storage failover. |
| Stop | Kubelet heartbeats stop. Kubernetes marks an unreachable node unhealthy and may taint it and evict Pods after the node-controller timeout; the exact timing is cluster configuration and must be recorded ([node lifecycle](https://kubernetes.io/docs/concepts/architecture/nodes/)). | The local path is unavailable while the node/container is stopped. `emptyDir` has no durability guarantee: Kubernetes only promises it across a container crash while the Pod remains; data is deleted when the Pod is removed from the node ([`emptyDir`](https://kubernetes.io/docs/concepts/storage/volumes/#emptydir)). | The lab can measure the unavailable/Unknown/Pending/eviction timeline. It cannot infer that data would be available on a different worker. |
| Restart | Starting the same kind node container allows its kubelet and node-local filesystem to return. Pods may recover if the node, Pod identity, and volume path all return before controller actions change the state; a long outage can instead lead to Pod replacement. | A local PV may reattach on that same node. Any observed `emptyDir` contents are an implementation/environment observation, not a durable storage contract. | The lab can prove same-node recovery and its timing for this kind/Docker setup. It cannot prove replicated durability. |
| Permanent removal | Removing the node container removes the kind node. A replacement node is a new scheduling/storage location unless the experiment deliberately preserves and remounts the underlying path. | A local PV’s node affinity can point at a node that no longer exists; the claim may remain bound while the volume is inaccessible. `emptyDir` is gone with the deleted Pod/node. Reclaim behavior is governed by the PV/StorageClass policy ([PV reclaim policy](https://kubernetes.io/docs/concepts/storage/persistent-volumes/#reclaim-policy)). | The lab can prove the stuck-Pod/PV-affinity outcome and the difference between API object retention and readable bytes. It cannot claim automatic cross-node recovery. |

The Kubernetes storage contract explains why this is so: local volumes depend on the
underlying node, require PV `nodeAffinity`, and can leave a Pod unable to run when that
node is unhealthy ([local volumes](https://kubernetes.io/docs/concepts/storage/volumes/#local)).
PV/PVC lifecycle is independent of an individual Pod, but that independence preserves the
claim-to-volume relationship; it does not relocate local storage
([persistent volumes](https://kubernetes.io/docs/concepts/storage/persistent-volumes/)).

## Component-by-component findings

### Local-path PVC and node affinity

For a local-path PV, record the PV’s `spec.nodeAffinity`, the selected node label, the
local path, the StorageClass reclaim policy, and the Pod events before and after each
action. The local-path provisioner’s normal node path map stores data on a node; its
documented `nodeAffinityKey` exists precisely to constrain access to the node on which
the path was provisioned ([local-path usage and configuration](https://github.com/rancher/local-path-provisioner#usage)).
Only an explicitly configured shared filesystem changes that premise; a shared path is a
different storage experiment, not automatic local-path replication
([local-path shared filesystem configuration](https://github.com/rancher/local-path-provisioner#configuration)).

The repository does not silently select local-path for every workload. The Matching
StatefulSet requests `storageClassName: simplematch-rwo-pod`, and the deployment contract
requires that class to support `ReadWriteOncePod` through CSI
([Matching StatefulSet](../../deploy/k8s/matching-statefulset.yaml),
[deployment storage contract](../../deploy/k8s/README.md#fixed-matching-fleet)). The
QuickFIX StatefulSet leaves `storageClassName` to the cluster default
([QuickFIX StatefulSet](../../deploy/k8s/quickfix-gateway-statefulset.yaml)). Thus a
local-path result for QuickFIX must identify the actual default StorageClass, while a
local-path result for Matching requires an explicit test configuration or an equivalent
StorageClass choice. Neither manifest by itself proves cross-node storage.

### `emptyDir`

Kubernetes creates `emptyDir` when a Pod is assigned to a node; all containers in that
Pod share it. It survives a container crash, but when the Pod is removed from the node for
any reason its contents are permanently deleted
([Kubernetes `emptyDir`](https://kubernetes.io/docs/concepts/storage/volumes/#emptydir)).
The Matching manifest uses `runtime-tmp` as `emptyDir`
([Matching StatefulSet](../../deploy/k8s/matching-statefulset.yaml)). It is therefore
appropriate for scratch/runtime data only. It is not a recovery source for Kafka,
PostgreSQL, Matching, or QuickFIX.

### StatefulSets

A StatefulSet gives each ordinal a stable identity and, through
`volumeClaimTemplates`, stable per-Pod storage. Kubernetes says that the identity and
associated storage persist across rescheduling, and that the PVC is not deleted merely
because the Pod or StatefulSet is deleted ([StatefulSet stable identity and storage](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)).
That guarantee is conditional on the associated PV being usable at the new location. A
local PV makes the scheduling constraint visible: the ordinal can be recreated while
remaining Pending because its one local volume is still attached to the old worker. A
StatefulSet does not create a second copy of the contents.

### Kafka replicas

Kafka’s durability boundary is partition replication across brokers, not the number of
Kubernetes Pods or PVCs. Kafka commits a record only after the required in-sync replicas
have applied it; a committed record remains available after a broker failure while an ISR
replica remains alive. `min.insync.replicas` combined with `acks=all` trades write
availability for that guarantee, and disabling unclean leader election avoids electing a
stale non-ISR replica at the cost of availability
([Kafka replication design](https://kafka.apache.org/43/design/design/),
[`min.insync.replicas`](https://kafka.apache.org/43/configuration/topic-configs/#min.insync.replicas),
[`unclean.leader.election.enable`](https://kafka.apache.org/43/configuration/topic-configs/#unclean.leader.election.enable)).

The checked-in local Kafka profile is deliberately RF1/min-ISR1 and marks itself
non-production-certifying ([local Kafka profile](../../config/kafka/matching-local.properties)).
It cannot prove that a worker loss preserves Kafka data. A future Kafka worker-loss test
must place brokers on distinct workers, use RF3 with a documented ISR policy, and verify
both committed data and the rejection/availability behavior during loss. Three PVCs on
one worker are still one failure domain.

### PostgreSQL

PostgreSQL WAL provides crash recovery and supports archive/PITR when a base backup and
WAL archive are retained ([PostgreSQL continuous archiving](https://www.postgresql.org/docs/current/continuous-archiving.html)).
That is different from cross-node HA. Streaming replication requires a standby; it is
asynchronous by default and can lose transactions not yet received by the standby, while
synchronous replication changes the durability/latency trade-off
([PostgreSQL warm standby](https://www.postgresql.org/docs/current/warm-standby.html)).
PostgreSQL also does not supply the complete primary-failure detection and promotion
orchestration by itself ([PostgreSQL failover](https://www.postgresql.org/docs/current/warm-standby-failover.html)).

The repository’s recorded local production-like gate uses a disposable local PostgreSQL
dependency and a kind application runtime; its documented target explicitly separates
the local gate from production PostgreSQL HA
([local production-like certification record](../production-live-certification.md)).
Consequently, a kind worker loss can test application disconnect/reconnect if PostgreSQL
remains alive, but it does not test PostgreSQL primary loss, PVC failover, standby
promotion, synchronous durability, backup/PITR, or cross-node replicated storage. Those
are future work even if a PostgreSQL Pod is later placed on a local-path PV.

### Matching replay

Matching’s checked-in contract makes Kafka the authoritative ordered journal and keeps
only bounded baseline/recovery coordinates on each ordinal’s PVC
([Matching deployment contract](../../services/docs/platform/deployment.md#fixed-matching-fleet),
[Matching deployment README](../../deploy/k8s/README.md#fixed-matching-fleet)). A
replacement waits for the old Lease to expire, acquires the ordinal’s Lease, replays the
retained Open Barrier, catches up to the Kafka watermark, and only then becomes Ready
([Matching recovery runbook](../../services/docs/platform/matching-fleet-recovery.md),
[Matching ingress contract](../../services/docs/architecture/matching-ingress.md)).

The lab can honestly prove that replay and fencing protocol with a normal Pod
delete/recreate while Kafka and the PVC remain available. It can also prove the negative
case for a local PV: if the worker is permanently removed and the PVC cannot be mounted,
the replacement cannot reach Ready, regardless of the correctness of the replay code. It
cannot claim that local-path preserves Matching state across workers; replay depends on
the authoritative Kafka data and a usable ordinal storage attachment.

### QuickFIX storage and data

QuickFIX/J distinguishes in-memory, file, and JDBC stores; its JDBC store persists session
state and messages in a relational database, while file-backed stores depend on local
disk ([QuickFIX/J architecture](https://quickfixj.org/docs/architecture/),
[QuickFIX/J configuration](https://quickfixj.org/docs/configuration/),
[QuickFIX/J `JdbcStoreFactory`](https://www.quickfixj.org/javadoc/2.3.0/quickfix/JdbcStoreFactory.html)).

This repository configures the acceptor with JDBC-backed QuickFIX/J factories
([acceptor configuration](../../config/quickfix/acceptor.cfg),
[JDBC acceptor factory](../../services/quickfix-gateway/src/main/java/com/simplematch/quickfixgateway/fix/QuickFixJdbcAcceptorFactory.java)).
If PostgreSQL remains available, a Gateway Pod restart does not by itself erase those
JDBC session records. The Gateway also owns a separate file WAL and recovery journal;
the default is `data/quickfix/wal/inbound.wal`, and the Kubernetes ConfigMap places it on
the `quickfix-data` mount ([Gateway file properties](../../services/quickfix-gateway/src/main/java/com/simplematch/quickfixgateway/config/QuickFixGatewayFileProperties.java),
[Gateway WAL appender](../../services/quickfix-gateway/src/main/java/com/simplematch/quickfixgateway/wal/WalAppender.java),
[Gateway WAL recovery journal](../../services/quickfix-gateway/src/main/java/com/simplematch/quickfixgateway/wal/WalRecoveryJournal.java),
[Gateway Kubernetes config](../../deploy/k8s/quickfix-gateway-configmap.yaml)). The
QuickFIX StatefulSet mounts that claim at `/var/lib/simplematch/quickfix-gateway`
([QuickFIX StatefulSet](../../deploy/k8s/quickfix-gateway-statefulset.yaml)).

If that claim uses local-path, a same-worker restart can restore the WAL and a normal
same-owner restart can exercise recovery. A permanent worker removal makes the WAL
unavailable unless its storage was separately preserved and remounted. This does not
provide active/standby FIX session continuity, fencing, route transfer, or a shared
cross-node WAL. Those remain future design and certification work; the repository’s
Gateway documentation also identifies standby promotion and route transfer as follow-up
work ([Gateway documentation](../../services/quickfix-gateway/docs/README.md)).

## What the local resilience lab can prove

### Existing evidence

The current checked-in evidence supports these bounded statements:

- The kind smoke can exercise the Matching Lease/PVC/replay contract and normal Pod
  recreation. The runbook explicitly describes the one-node/RF1 setup as
  non-certifying ([local production-like certification record](../production-live-certification.md)).
- The application manifests and official Kubernetes semantics explain what a Pod,
  StatefulSet PVC, `emptyDir`, and local PV should do under a controlled action.
- The repository’s QuickFIX implementation separates JDBC session storage from the
  Gateway-local WAL, so a normal restart with PostgreSQL and the claim still available is
  a narrower recovery test.

Those statements are repository evidence and contract analysis, not a claim that a kind
worker was drained, stopped, restarted, or removed during this research.

### Required future local experiment

To turn the analysis into a reproducible local resilience result, the lab must explicitly
use at least two kind workers, identify the actual StorageClass/provisioner, and record
the PV `nodeAffinity` and underlying path. For each worker action, it should capture
`kubectl get nodes`, Pod phase and events, PVC/PV status, Lease state, Kafka ISR/offset
observations, PostgreSQL connectivity, and checksums/record counts for the local-path
volume and `emptyDir`. The experiment must distinguish stopping the same node container
from deleting and recreating a new node container.

That experiment can prove local behavior such as “same worker returns the same path” or
“replacement remains Pending because affinity names the removed worker.” It still cannot
be promoted to a production HA claim: kind’s nodes are Docker containers and its own
documentation describes the cluster as a local testing environment without external
state management ([kind design principles](https://kind.sigs.k8s.io/docs/design/principles/)).

## Future cross-node replicated-storage outlook

The following are outside this ticket and must not be implied by a successful local
worker-loss run:

- replicated/CSI storage that can attach the same durable data on another worker, with
  explicit failure, fencing, and reclaim semantics;
- Kafka brokers placed on distinct failure domains with RF3, ISR/min-ISR and producer
  acknowledgement evidence;
- PostgreSQL primary/standby replication, promotion, fencing, backups, and PITR;
- Matching replay under actual worker loss after Kafka remains authoritative and the
  ordinal’s storage/recovery contract is proven;
- QuickFIX active/standby ownership, shared or replicated JDBC/WAL state, FIX session
  fencing, route transfer, and a real counterparty reconnect/resend test.

The decision for #144 is therefore: use the local lab to demonstrate node affinity,
eviction, same-node restart, stuck local-PV recovery, and the application-level replay
contracts; keep cross-node durability and failover as a separately designed and
certified future capability. Issue #141 is intentionally not modified here.

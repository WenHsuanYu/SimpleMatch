# Kafka 4.3.1 and Debezium 3.6.0.Final on multinode kind without an operator

Research date: 2026-08-13
GitHub issue: [WenHsuanYu/SimpleMatch#143](https://github.com/WenHsuanYu/SimpleMatch/issues/143)
Scope: one kind control-plane node, three kind worker nodes, three combined KRaft broker/controllers, and two Debezium Kafka Connect workers using ordinary Kubernetes resources.

## Resolution

Yes. A fixed, no-operator lab can run this topology with ordinary Kubernetes resources:

- one namespace;
- a three-replica Kafka StatefulSet, one PVC per pod, a Kafka headless Service, and a client bootstrap Service;
- a two-replica Kafka Connect worker workload, a Connect Service, ConfigMaps, and Secrets;
- PodDisruptionBudgets, topology-aware scheduling, and startup/readiness/liveness probes;
- one-time initialization and administrative Jobs or explicitly run scripts for KRaft storage formatting, internal-topic creation, and connector registration.

This is a supported use of Kubernetes primitives and the Kafka/Debezium configuration model for a fixed test topology, not an operator-free production control plane. Kafka documents combined broker/controller mode as simpler for small or development deployments and not recommended for critical deployments because the roles cannot be scaled or rolled independently. ([Kafka KRaft](https://kafka.apache.org/43/operations/kraft/))

The three Kafka pods must retain stable identities and stable per-pod addresses. The two Connect workers must share one distributed Connect group and the same replicated internal topics. Kubernetes can provide the resource primitives, placement, DNS, PVC claims, and probes; it does not provide Kafka quorum membership, storage formatting, topic replication, connector lifecycle, or safe Kafka reconfiguration.

## Version decision

| Component | Version to use as of 2026-08-13 | Evidence and qualification |
| --- | --- | --- |
| Apache Kafka | 4.3.1 | The official Kafka downloads page lists 4.3.1 as a supported release, released 2026-06-25, and lists the official apache/kafka:4.3.1 image. ([Kafka downloads](https://kafka.apache.org/community/downloads/), [Kafka 4.3.1 release announcement](https://kafka.apache.org/blog/2026/06/25/apache-kafka-4.3.1-release-announcement/)) |
| Debezium | 3.6.0.Final | Debezium’s official releases page lists the 3.6 series, released 2026-07-01, and its 3.6 page lists 3.6.0.Final. No later 3.6 patch release is listed there. ([Debezium releases](https://debezium.io/releases/), [Debezium 3.6 releases](https://debezium.io/releases/3.6/), [Debezium 3.6 final announcement](https://debezium.io/blog/2026/07/01/debezium-3-6-final-release/)) |
| Debezium/Kafka compatibility | Same 4.3 line, with a validation caveat | The Debezium 3.6 release notes say the release was built against Kafka Connect 4.3.0 and tested with Kafka broker 4.3.0. They do not claim an explicit 4.3.1 test. Kafka 4.3.1 is the requested current patch release in the same 4.3 line, but this exact pair should be smoke-tested in this lab rather than represented as an upstream certification. ([Debezium 3.6 release notes](https://debezium.io/docs/releases/), [Kafka compatibility](https://kafka.apache.org/43/getting-started/compatibility/)) |

For reproducibility, pin immutable image digests after pulling from the official registries instead of relying only on mutable tags. On 2026-08-13, the official registry manifests observed for the requested tags were apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837 and quay.io/debezium/connect:3.6.0.Final@sha256:698f0559e667a242f962221079e75917b2b7a3ad4de62661e977628da0e33b45; re-check these values immediately before deployment. ([Apache Kafka image tags](https://hub.docker.com/r/apache/kafka/tags?name=4.3.1), [Debezium Connect image tags](https://quay.io/repository/debezium/connect?tab=tags))

The official Debezium container image is useful for this lab, but Debezium states that its Quay images do not undergo rigorous testing or security analysis and are intended for testing and evaluation, not production. Build, scan, and own a production image with the required connector plugins instead. ([Debezium installation and container images](https://debezium.io/documentation/reference/3.6/install.html))

## Ordinary Kubernetes resource layout

The official kind configuration for one control-plane and three workers is four nodes with roles control-plane, worker, worker, and worker. Kind nodes are Docker containers and worker nodes provide limited isolation; this is suitable for rolling-update and failure-exercise testing, not independent physical failure domains. ([kind configuration](https://kind.sigs.k8s.io/docs/user/configuration/), [kind quick start](https://kind.sigs.k8s.io/docs/user/quick-start/))

| Resource | Count and important fields | Why it is needed |
| --- | --- | --- |
| Namespace | 1 | Keeps names, Secrets, and administrative Jobs scoped. |
| Kafka headless Service | 1, clusterIP: None, publishNotReadyAddresses: true, selector for Kafka pods | Headless DNS returns individual pod addresses rather than a virtual IP. Publishing not-ready addresses lets a StatefulSet peer quorum discover all members before readiness is true. ([Kubernetes Service](https://kubernetes.io/docs/concepts/services-networking/service/), [Service API: publishNotReadyAddresses](https://kubernetes.io/docs/reference/kubernetes-api/core/service-v1/)) |
| Kafka client Service | 1 ordinary ClusterIP bootstrap Service | Gives in-cluster clients a seed address; clients must still receive and use each broker’s per-pod advertised address. Do not advertise this one virtual address as every broker identity. |
| Kafka StatefulSet | 1 with replicas: 3, serviceName set to the headless Service, podManagementPolicy: Parallel | Provides stable ordinal identity, stable network identity, and a PVC claim per pod. Parallel avoids the default OrderedReady dependency during quorum bootstrap. ([StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)) |
| Kafka PVCs | 3 claims from volumeClaimTemplates | Each broker must retain its own log and KRaft metadata directory across a pod restart. PVCs are not automatically replicated across kind workers; the StorageClass/provisioner determines whether the data survives a worker loss. ([Persistent Volumes](https://kubernetes.io/docs/concepts/storage/persistent-volumes/), [StorageClasses](https://kubernetes.io/docs/concepts/storage/storage-classes/)) |
| Kafka PodDisruptionBudget | 1, minAvailable: 2 | Limits voluntary evictions to one Kafka pod at a time. A PDB does not protect against direct pod deletion, a crashed process, or a failed node. ([Pod disruptions](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/), [PDB task](https://kubernetes.io/docs/tasks/run-application/configure-pdb/)) |
| Kafka ConfigMap plus Secret | 1 or more | Store rendered configuration and the cluster ID reference in ordinary resources; put TLS/SASL keys, passwords, and source-database credentials in Secrets. |
| Connect worker workload | 2 replicas, preferably a StatefulSet with a headless Service for stable worker DNS; a Deployment is also possible when each worker advertises a reachable pod IP | Kafka Connect distributed mode stores worker state in Kafka and automatically balances work and recovers from worker failure. Stable per-worker REST addresses make the Kubernetes layout less dependent on changing pod IPs. ([Kafka Connect overview](https://kafka.apache.org/43/kafka-connect/overview/), [Kafka Connect user guide](https://kafka.apache.org/43/kafka-connect/user-guide/)) |
| Connect PodDisruptionBudget | 1, minAvailable: 1 | Retains one worker during a voluntary eviction. It is not a failure detector or storage guarantee. ([Pod disruptions](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/)) |
| Connect Service | 1 ordinary ClusterIP, plus a headless Service if Connect uses stable worker DNS | Exposes the REST API for administrative registration and health checks. Keep it in-cluster and secure it for anything beyond a toy lab. Kafka Connect REST is unsecured by default. ([Kafka Connect user guide: REST API](https://kafka.apache.org/43/kafka-connect/user-guide/)) |
| Connect ConfigMap plus Secret | 1 or more | Common worker configuration belongs in a ConfigMap; connector credentials and TLS material belong in Secrets. |
| Initialization/admin Jobs | Explicit, one-shot Jobs or administrator-run scripts | Format blank Kafka volumes once, create replicated internal topics before workers start, and register connector configurations through the Connect REST API. Kubernetes Jobs do not make these operations safe or idempotent automatically. ([Kafka KRaft](https://kafka.apache.org/43/operations/kraft/), [Kafka Connect user guide](https://kafka.apache.org/43/kafka-connect/user-guide/)) |

### Placement on the kind nodes

Label the three worker nodes and constrain Kafka to workers. Do not rely on the control-plane node as an application node. Use required pod anti-affinity or a required topology spread constraint on kubernetes.io/hostname so that the three Kafka replicas occupy different kind workers while all three are schedulable. ([Kubernetes inter-pod affinity and anti-affinity](https://kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node/), [topology spread constraints](https://kubernetes.io/docs/concepts/scheduling-eviction/topology-spread-constraints/))

The two Connect workers can use preferred anti-affinity or a spread constraint and may share workers with Kafka. Requiring every application pod to occupy a distinct node would be unsatisfiable on three workers. During a one-worker loss, Kubernetes may not be able to restore all three Kafka replicas; the remaining two can preserve the KRaft quorum while the third is unavailable.

A PVC claim is necessary for a restart test, but a claim alone does not create replicated storage. A local or host-path-backed volume can leave a broker’s data stranded when its kind worker container disappears. Treat that as a storage-failure limitation of the lab, and verify the StorageClass and reclaim/attachment behavior before calling a worker-loss test durable. ([Kubernetes local volumes](https://kubernetes.io/docs/concepts/storage/volumes/#local), [StorageClasses](https://kubernetes.io/docs/concepts/storage/storage-classes/))

## Kafka configuration

Use one fixed cluster ID and static membership for this fixed three-broker lab. Kafka’s KRaft documentation describes broker,controller combined roles, static controller.quorum.voters, and the newer dynamic controller.quorum.bootstrap.servers mode. Do not set both quorum mechanisms. Static voters are the smaller reviewable configuration for a topology that is intentionally not being scaled. ([Kafka KRaft](https://kafka.apache.org/43/operations/kraft/))

The following is a configuration shape, not a drop-in manifest. The ordinal and advertised DNS name must be rendered separately for each StatefulSet pod:

```properties
process.roles=broker,controller
node.id=<statefulset-ordinal>
controller.quorum.voters=0@kafka-0.kafka-headless.<namespace>.svc.cluster.local:9093,1@kafka-1.kafka-headless.<namespace>.svc.cluster.local:9093,2@kafka-2.kafka-headless.<namespace>.svc.cluster.local:9093

listeners=INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
advertised.listeners=INTERNAL://kafka-<statefulset-ordinal>.kafka-headless.<namespace>.svc.cluster.local:9092
listener.security.protocol.map=INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT
inter.broker.listener.name=INTERNAL
controller.listener.names=CONTROLLER
log.dirs=/var/lib/kafka/data

default.replication.factor=3
min.insync.replicas=2
unclean.leader.election.enable=false
controlled.shutdown.enable=true
offsets.topic.replication.factor=3
transaction.state.log.replication.factor=3
transaction.state.log.min.isr=2
auto.create.topics.enable=false
```

The relevant invariants are:

1. node.id must be unique and stable for the life of the broker’s data directory. Derive it from the StatefulSet ordinal; never hard-code one ID into all three pods. Kafka documents node.id as required for KRaft and process.roles as the role declaration. ([Kafka broker configurations](https://kafka.apache.org/43/configuration/broker-configs/))
2. advertised.listeners must contain each broker’s own stable pod FQDN, not localhost, a pod IP that can change, or one shared Service IP. Kafka documents advertised listeners as the addresses published for clients and other brokers. The controller listener is separate from the inter-broker listener. ([Kafka broker configurations](https://kafka.apache.org/43/configuration/broker-configs/), [Kafka listener configuration](https://kafka.apache.org/43/security/listener-configuration/))
3. controller.quorum.voters must enumerate all three controller IDs and their controller listener endpoints. Three controllers retain quorum after one controller loss because a majority of two remains. ([Kafka KRaft](https://kafka.apache.org/43/operations/kraft/))
4. Generate the cluster ID once and format all three blank data directories with that same ID. On restart, detect and reuse an existing meta.properties; do not generate a new ID per pod or reformat an existing PVC. Kafka documents explicit storage formatting and warns against automatically formatting blank storage directories. ([Kafka KRaft](https://kafka.apache.org/43/operations/kraft/))
5. Use replication factor 3, min.insync.replicas=2, and producer acks=all for the durability assertion. Kafka’s min.insync.replicas rule rejects acks=all writes when the ISR falls below the configured minimum; it does not make a producer using weaker acknowledgements durable. The transaction and consumer-offset internal topics also need replication factor 3 and enough ISR. ([Kafka broker configurations](https://kafka.apache.org/43/configuration/broker-configs/))
6. Disable unclean leader election for the durability exercise. Controlled shutdown is useful for a graceful pod eviction, but it does not cover a crash or node loss. Kafka’s operations guide distinguishes graceful shutdown from failure handling and requires replicas to remain available for a controlled shutdown. ([Kafka basic operations](https://kafka.apache.org/43/operations/basic-kafka-operations/))

For a fixed lab, a render/init step can use the ordinal, pod FQDN, and one shared cluster ID to prepare each pod. The initial format operation must be reviewed as a one-time, idempotent action. Dynamic KRaft membership and automated scale/roll behavior require additional lifecycle logic; they are not supplied by a StatefulSet.

## Discovery and listeners

The Kafka headless Service supplies DNS records for individual StatefulSet pods. Kubernetes documents the stable pod DNS form for a StatefulSet as the pod name plus the headless Service and namespace domain. publishNotReadyAddresses is appropriate for quorum peer discovery because it publishes endpoints before readiness; it must not be mistaken for application health. ([StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/), [Kubernetes Service](https://kubernetes.io/docs/concepts/services-networking/service/), [Service API: publishNotReadyAddresses](https://kubernetes.io/docs/reference/kubernetes-api/core/service-v1/))

Use separate listeners:

- CONTROLLER is for KRaft controller quorum traffic and is not a client bootstrap listener.
- INTERNAL is the broker-to-broker and in-cluster client listener.
- A host-facing listener is optional for this in-cluster lab. If host tools need it, each broker needs a distinct reachable address and matching advertised.listeners; one shared NodePort cannot represent three broker identities.
- PLAINTEXT is acceptable only inside an isolated toy lab. Use TLS/SASL and Secrets for any environment that carries real data or credentials. Kafka’s listener documentation defines the listener-to-security-protocol map and the separation of controller and inter-broker listeners. ([Kafka listener configuration](https://kafka.apache.org/43/security/listener-configuration/))

The Connect workers should bootstrap from all three broker addresses, for example:

```properties
bootstrap.servers=kafka-0.kafka-headless.<namespace>.svc.cluster.local:9092,kafka-1.kafka-headless.<namespace>.svc.cluster.local:9092,kafka-2.kafka-headless.<namespace>.svc.cluster.local:9092
```

Kafka documents bootstrap.servers as an initial broker list and recommends more than one address so a client can connect if one broker is unavailable. ([Kafka Connect configurations](https://kafka.apache.org/43/configuration/kafka-connect-configs/))

## Kafka Connect and Debezium configuration

Run both workers in distributed mode with the same group.id and the same internal topics. Create these topics before starting the workers so their partition counts, compaction, replication, and ISR policy are explicit:

```properties
group.id=debezium-connect
config.storage.topic=connect-configs
config.storage.replication.factor=3
offset.storage.topic=connect-offsets
offset.storage.partitions=25
offset.storage.replication.factor=3
status.storage.topic=connect-status
status.storage.partitions=3
status.storage.replication.factor=3

plugin.path=/kafka/connect
listeners=http://0.0.0.0:8083
rest.advertised.host.name=<stable-worker-dns-or-reachable-pod-address>
rest.advertised.port=8083
```

Kafka Connect documents the distributed worker group and its compacted config, offset, and status topics; its generated configuration documents the storage replication-factor settings and REST advertised address. The exact offset/status partition counts are workload choices; the invariant here is one config partition, multiple offset/status partitions as appropriate, and replication factor 3 for all three internal topics. ([Kafka Connect user guide](https://kafka.apache.org/43/kafka-connect/user-guide/), [Kafka Connect generated configuration](https://kafka.apache.org/43/generated/connect_config.html))

A two-replica Connect StatefulSet with a headless Service gives each worker a stable name for rest.advertised.host.name without local durable storage. A two-replica Deployment is also valid for stateless workers if the pod IP is injected and advertised as a reachable address, and if worker restarts are expected to rejoin the same Connect group. In distributed mode, the REST endpoint is used for worker communication as well as administration, so advertising a shared Service address for both workers is incorrect. ([Kafka Connect user guide: REST API](https://kafka.apache.org/43/kafka-connect/user-guide/))

Use quay.io/debezium/connect:3.6.0.Final for this research lab, with the connector plugin path configured and the tag preferably pinned to the verified digest above. Debezium documents installing connector artifacts into the Kafka Connect plugin path and registering connectors through the Connect REST API. The official Debezium image’s testing/evaluation qualification still applies. ([Debezium installation](https://debezium.io/documentation/reference/3.6/install.html))

For a relational connector that uses schema history, create its schema-history topic with the required single partition, replication factor 3, long retention, and no compaction. Debezium documents these production-oriented schema-history requirements and the fact that source offsets are stored in a compacted Kafka topic. Connector-specific topics should likewise be planned and replicated rather than left to accidental auto-creation. ([Debezium storage](https://debezium.io/documentation/reference/3.6/configuration/storage.html), [Debezium installation](https://debezium.io/documentation/reference/3.6/install.html))

Register connector configurations with POST /connectors or equivalent REST calls after the Connect cluster is ready. Without Strimzi or another operator there is no Kafka custom resource, KafkaConnect custom resource, or KafkaConnector custom resource; the REST request and its idempotent ownership remain an explicit lab step. Debezium’s Kubernetes guidance uses Strimzi custom resources and its operator for that declarative workflow. ([Debezium Kubernetes](https://debezium.io/documentation/reference/stable/operations/kubernetes.html), [Kafka Connect user guide](https://kafka.apache.org/43/kafka-connect/user-guide/))

## Health and observability

Use Kubernetes probes for process-level admission to traffic, and separate cluster-level checks for the claims being tested:

- Kafka startupProbe: allow storage recovery and KRaft election time. Kubernetes documents that a startup probe suppresses liveness/readiness checks until startup succeeds. ([Kubernetes probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-probes/))
- Kafka readinessProbe: run a broker API/admin check that proves the pod can serve broker traffic. Do not make every remaining broker unready merely because another broker is down; after one loss, two brokers should remain serviceable under the intended test.
- Kafka cluster check: run kafka-metadata-quorum.sh ... describe --status and kafka-topics.sh --describe from an admin container or Job. Kafka documents the metadata-quorum status command and topic description as the way to inspect quorum and ISR state. ([Kafka KRaft](https://kafka.apache.org/43/operations/kraft/), [Kafka basic operations](https://kafka.apache.org/43/operations/basic-kafka-operations/))
- Connect readinessProbe: call GET / on the worker. Kafka Connect documents this endpoint as returning worker version and the Kafka cluster ID. Keep the REST Service private and add authentication/TLS outside the toy lab. ([Kafka Connect user guide](https://kafka.apache.org/43/kafka-connect/user-guide/))
- Connector check: call the REST status endpoint for each registered connector and task; a live worker process does not prove that a Debezium task is reading the source.

## Controlled one-broker-loss procedure and expected result

Run this as a controlled process/pod test, not as a claim about independent physical hosts:

1. Start three Kafka brokers, confirm a three-member metadata quorum, and confirm RF3 topics have ISR 3. Start both Connect workers and confirm both are in the same distributed group.
2. Produce and consume a test topic with RF3, min.insync.replicas=2, and producer acks=all. Confirm the Connect internal topics and any Debezium schema-history/topic configuration have RF3.
3. Gracefully evict or delete exactly one Kafka pod, or drain one kind worker for the failure exercise. A graceful shutdown can transfer leadership; a direct deletion exercises failure recovery. A PDB constrains voluntary eviction but does not constrain direct deletion or an involuntary node/container failure. ([Kafka basic operations](https://kafka.apache.org/43/operations/basic-kafka-operations/), [Kubernetes pod disruptions](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/))
4. Expected Kafka result: two of three combined broker/controllers remain, so the KRaft controller majority remains. RF3 data topics temporarily have ISR 2; acks=all writes continue because the ISR meets min.insync.replicas=2. A second broker loss is outside this write-availability guarantee.
5. Expected Connect result: the two-worker group rebalances if the failed worker was hosting work; replicated internal topics preserve worker state. A connector with one task is still one task and cannot become two active source readers just because two workers exist.
6. Restore the same broker ordinal and its PVC, or explicitly document that the local kind storage test cannot reattach it. Wait for ISR 3 and the metadata quorum to recover, then rerun the topic and connector checks.

Kafka documents that three controllers tolerate one controller failure and that replication factor 3 provides replica redundancy. The min.insync.replicas guarantee applies to acks=all; it is a write-availability assertion for one loss, not a claim that all three copies are simultaneously available. ([Kafka KRaft](https://kafka.apache.org/43/operations/kraft/), [Kafka broker configurations](https://kafka.apache.org/43/configuration/broker-configs/), [Kafka basic operations](https://kafka.apache.org/43/operations/basic-kafka-operations/))

## Supported, toy-lab-only, and operator-dependent boundaries

| Choice | Classification for this ticket | Boundary |
| --- | --- | --- |
| Kafka 4.3.1, KRaft, three combined broker/controllers, static three-member quorum | Supported for the fixed lab; not a critical-production topology | Kafka explicitly permits combined roles and describes them as simpler for small/dev deployments, while warning against critical deployments. ([Kafka KRaft](https://kafka.apache.org/43/operations/kraft/)) |
| StatefulSet, headless DNS, stable per-pod node.id, PVCs, RF3, min.insync.replicas=2, acks=all, explicit internal topics | Supported Kubernetes/Kafka primitives for a fixed test | These choices align with the official StatefulSet, Service, Kafka broker, and Connect configuration contracts. ([StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/), [Kafka broker configurations](https://kafka.apache.org/43/configuration/broker-configs/), [Kafka Connect user guide](https://kafka.apache.org/43/kafka-connect/user-guide/)) |
| Two distributed Connect workers, Debezium 3.6.0.Final, connector registration through REST | Supported fixed-lab workflow | Kafka Connect documents distributed workers and REST administration; Debezium documents REST installation/registration. The exact Kafka 4.3.1 pairing remains a lab validation item because Debezium’s release evidence names 4.3.0. ([Kafka Connect overview](https://kafka.apache.org/43/kafka-connect/overview/), [Debezium installation](https://debezium.io/documentation/reference/3.6/install.html), [Debezium release notes](https://debezium.io/docs/releases/)) |
| kind’s one control-plane plus three Docker-container workers | Toy lab / integration test | Kind documents limited isolation; this does not model independent host, rack, or zone failures. ([kind configuration](https://kind.sigs.k8s.io/docs/user/configuration/)) |
| Kafka combined roles for a production service, automatic scale/roll, or critical durability claim | Not supported by this design | Use separated roles and an operational lifecycle design; the fixed combined topology cannot independently roll or scale controllers and brokers. ([Kafka KRaft](https://kafka.apache.org/43/operations/kraft/)) |
| PLAINTEXT, host-path/local kind storage, one shared NodePort, unscanned Quay image, emptyDir for Kafka logs | Toy-lab-only | These remove security, identity, storage, or image-assurance properties required for a production claim. ([Kafka listener configuration](https://kafka.apache.org/43/security/listener-configuration/), [Debezium installation](https://debezium.io/documentation/reference/3.6/install.html), [Kubernetes Persistent Volumes](https://kubernetes.io/docs/concepts/storage/persistent-volumes/)) |
| Dynamic KRaft membership, safe rolling upgrades, storage replication/reattachment, topic reassignment, ACL/TLS rotation, declarative connector/topic/user reconciliation, automatic drift healing | Requires an operator or equivalent explicit automation | Ordinary StatefulSets and Jobs do not understand Kafka quorum transitions or connector semantics. Debezium recommends Strimzi for Kubernetes and documents its custom-resource/operator model. ([Debezium Kubernetes](https://debezium.io/documentation/reference/stable/operations/kubernetes.html), [Kafka KRaft](https://kafka.apache.org/43/operations/kraft/)) |

Thus an operator is not technically required to start and exercise this exact fixed topology. An operator becomes the appropriate boundary when the requirement changes from “run and test these five fixed processes” to “continuously reconcile Kafka, Connect, connectors, storage, security, upgrades, and failure recovery.” The no-operator result should not be represented as production certification.

## Acceptance checklist

The research decision is satisfied only if an implementation can show all of the following:

- [ ] The kind cluster has one control-plane node and three workers, with Kafka pods spread across workers.
- [ ] Kafka pod names, node.id values, controller voter IDs, advertised listener FQDNs, and PVCs remain one-to-one and stable.
- [ ] All three KRaft data directories use one cluster ID and are not reformatted on restart.
- [ ] Metadata quorum status shows three members before the test and two-member majority after exactly one broker loss.
- [ ] Data and Connect internal topics have RF3; the test producer uses acks=all; RF3 topics retain ISR 2 after one loss.
- [ ] Both Connect workers use one group.id, all three Kafka bootstrap addresses, and the same replicated internal topics.
- [ ] GET / and connector status checks are green before and after worker rebalance.
- [ ] One broker-loss recovery is reported separately from the kind worker-container/local-storage limitation.
- [ ] The final report labels the combined-role kind setup, PLAINTEXT, local storage, and official Debezium image as lab-only choices.
- [ ] No operator-dependent lifecycle claim is hidden inside an ordinary StatefulSet or Job.

## Primary sources

All research sources used above are official Apache Kafka, Debezium, Kubernetes, kind, Docker Hub, or Quay sources:

- [Apache Kafka downloads](https://kafka.apache.org/community/downloads/)
- [Apache Kafka 4.3.1 release announcement](https://kafka.apache.org/blog/2026/06/25/apache-kafka-4.3.1-release-announcement/)
- [Kafka 4.3 KRaft](https://kafka.apache.org/43/operations/kraft/)
- [Kafka 4.3 broker configurations](https://kafka.apache.org/43/configuration/broker-configs/)
- [Kafka 4.3 listener configuration](https://kafka.apache.org/43/security/listener-configuration/)
- [Kafka 4.3 basic operations](https://kafka.apache.org/43/operations/basic-kafka-operations/)
- [Kafka 4.3 Connect configurations](https://kafka.apache.org/43/configuration/kafka-connect-configs/)
- [Kafka 4.3 generated Connect configuration](https://kafka.apache.org/43/generated/connect_config.html)
- [Kafka 4.3 Connect user guide](https://kafka.apache.org/43/kafka-connect/user-guide/)
- [Debezium releases](https://debezium.io/releases/)
- [Debezium 3.6 releases](https://debezium.io/releases/3.6/)
- [Debezium 3.6 release notes](https://debezium.io/docs/releases/)
- [Debezium 3.6 installation](https://debezium.io/documentation/reference/3.6/install.html)
- [Debezium 3.6 storage](https://debezium.io/documentation/reference/3.6/configuration/storage.html)
- [Debezium Kubernetes operations](https://debezium.io/documentation/reference/stable/operations/kubernetes.html)
- [kind configuration](https://kind.sigs.k8s.io/docs/user/configuration/)
- [Kubernetes StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- [Kubernetes Services](https://kubernetes.io/docs/concepts/services-networking/service/)
- [Kubernetes service API](https://kubernetes.io/docs/reference/kubernetes-api/core/service-v1/)
- [Kubernetes persistent volumes](https://kubernetes.io/docs/concepts/storage/persistent-volumes/)
- [Kubernetes storage classes](https://kubernetes.io/docs/concepts/storage/storage-classes/)
- [Kubernetes probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-probes/)
- [Kubernetes pod disruptions](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/)
- [Docker Hub Apache Kafka image tags](https://hub.docker.com/r/apache/kafka/tags?name=4.3.1)
- [Quay Debezium Connect image tags](https://quay.io/repository/debezium/connect?tab=tags)

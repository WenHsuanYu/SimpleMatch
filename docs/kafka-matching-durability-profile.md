# Matching Kafka durability profile

The repository owns two explicit profiles for the Kafka-backed Matching journals:
`config/kafka/matching-production.properties` and `config/kafka/matching-local.properties`. The
production profile is the production-shaped contract used by the repository-local gate. It is not
an external production certification. Here, journal means the ordered Kafka topics
`matching.commands` and `matching.events`; it does not mean a local file or PVC fsync journal inside
the Matching hot path.

| Setting | Production-shaped local gate | Local single broker |
| --- | ---: | ---: |
| Partitions per topic | 15 | 15 |
| Replication factor | 3 | 1 |
| Minimum ISR | 2 | 1 |
| Cleanup policy | `delete` | `delete` |
| Retention | 2,592,000,000 ms (30 calendar days) | same |
| Automatic topic creation | disabled | disabled |
| Unclean leader election | disabled | disabled |
| Producer acknowledgement | `acks=all` + idempotence | same |
| Local RF3 durability gate | allowed | rejected |

The profile applies independently and exactly to `matching.commands` and `matching.events`. Neither topic is compacted. The record key is not an ownership mechanism: the artifact declares the numeric partition explicitly, and Matching ordinal N owns partition N.

## Provision and verify

For the repository-local three-broker production-like cluster, provision the two topics with the
production-shaped profile and then validate their effective state. The `--certify-production` flag
is retained as a fail-closed assertion that the RF3 profile is selected; it does not claim that an
external production certification occurred.

~~~bash
bash scripts/provision-matching-topics.sh \
  --bootstrap-server localhost:19092,localhost:19093,localhost:19094 \
  --profile production \
  --broker-config-file out/certification/local-production-like/kafka-fixture/broker.config.txt \
  --producer-config-file scripts/testdata/matching-topic-profile/valid/matching.producer.config.txt \
  --capacity-evidence-file scripts/testdata/matching-topic-profile/valid/capacity.properties \
  --certify-production
~~~

For a Kafka listener that requires TLS/SASL, pass `--command-config` from a secure, uncommitted
file. It is an environment input and must never be printed or committed. The same option is
accepted by both scripts.

The local deployment must configure the broker safety properties before provisioning:

```bash
bash scripts/provision-matching-topics.sh \
  --bootstrap-server localhost:19092,localhost:19093,localhost:19094 \
  --profile production \
  --broker-config-file out/certification/local-production-like/kafka-fixture/broker.config.txt \
  --producer-config-file scripts/testdata/matching-topic-profile/valid/matching.producer.config.txt \
  --capacity-evidence-file scripts/testdata/matching-topic-profile/valid/capacity.properties \
  --certify-production
```

The broker-config file is a read-only effective configuration export when static broker properties are not returned by the Kafka CLI. It must include, at minimum:

```properties
auto.create.topics.enable=false
unclean.leader.election.enable=false
```

The provisioner creates the two topics with explicit partition, replication, cleanup, retention, and
minimum-ISR settings, then invokes the fail-closed validator. The validator checks every partition
description (including replica/ISR membership and leader state), exact topic configuration, effective
broker safety settings, producer requirements, and optional workload capacity evidence. It rejects a
single-broker profile when `--certify-production` is supplied.

For a no-write review of the generated Kafka CLI calls:

```bash
bash scripts/provision-matching-topics.sh \
  --bootstrap-server kafka:9092 --profile production --certify-production --dry-run
```

The one-broker Compose service intentionally uses the local profile and disables implicit topic
creation and unclean election. It is useful for developer checks, but cannot satisfy the local RF3
durability gate.

## Workload-based retention and disk evidence

The local gate records workload evidence after compression at the Kafka-record boundary. The
evidence file is a measured input to the validator, not a claim that an external cluster was
certified:

```properties
workload.commands.per.day=1000000
workload.events.per.day=1000000
workload.average.command.record.bytes=512
workload.average.event.record.bytes=768
capacity.broker.count=3
capacity.usable.cluster.bytes=200000000000
capacity.usable.broker.bytes=70000000000
```

`capacity.usable.broker.bytes` is the smallest usable budget among the three local brokers. The
profile supplies the 30-day retention and 30% headroom constants. The validator independently
computes:

```text
logicalBytesPerDay =
    commandsPerDay × averageCommandRecordBytes
  + eventsPerDay   × averageEventRecordBytes

replicatedThirtyDayBytes = logicalBytesPerDay × 30 × 3
requiredUsableClusterBytes = replicatedThirtyDayBytes / 0.70
requiredUsableBrokerBytes = logicalBytesPerDay × 30 / 0.70
```

The final division reserves 30% operational headroom for partition skew, index files, broker
rebalancing, and normal variance. The local check fails if either the cluster budget or the smallest
broker budget is below the independently calculated requirement. Missing, zero, non-numeric, or
replication-factor-mismatched evidence fails closed.

## Broker and ISR failure behavior

The validator models the local failure matrix from Kafka topic-describe output:

| Scenario | Expected local result |
| --- | --- |
| All three replicas and ISR members available | Valid |
| One broker unavailable, two ISR members remain, leader remains in ISR | Valid; the topic stays writable under `min.insync.replicas=2` |
| Two brokers unavailable, only one ISR member remains | Rejected; readiness fails closed |
| Leader is absent, outside the replica set, or outside the ISR | Rejected |
| ISR contains a broker outside the assigned replicas or duplicates a broker | Rejected |

These are repository-local fixture checks. A later environment owner may reuse the same validator
against a staged or production-shaped cluster, but that external activity is a promotion template,
not a completion condition for this project.

Alert immediately on under-replicated partitions, any ISR below 2, topic/profile drift, disabled idempotence or `acks!=all`, automatic topic creation, unclean election, or disk consumption beyond the certified headroom. Future Gateway admission automation consumes those signals in #135; this profile deliberately does not make a local shell script claim to pause live trading.

Run the repository fixture checks with:

```bash
bash scripts/test-matching-topic-profile.sh
```

The tests cover the valid RF3/ISR2 profile, one- and two-broker loss, absent leaders, invalid
replica/ISR membership, unsafe broker policy, insufficient capacity, unsafe producer settings,
refusal to use the RF1 local profile, command-config forwarding, and generated provisioning
commands. The local production-like certification run remains the project-level integration gate.
External cluster ownership, external credentials, and external production measurements remain
promotion-template inputs; they are not required to close this local issue.

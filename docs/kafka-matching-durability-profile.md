# Matching Kafka durability profile

The repository owns two explicit profiles for the Kafka-backed Matching journals:
`config/kafka/matching-production.properties` and `config/kafka/matching-local.properties`. The
production profile is the only one that can pass production certification. Here, journal means the
ordered Kafka topics `matching.commands` and `matching.events`; it does not mean a local file or
PVC fsync journal inside the Matching hot path.

| Setting | Production | Local single broker |
| --- | ---: | ---: |
| Partitions per topic | 15 | 15 |
| Replication factor | 3 | 1 |
| Minimum ISR | 2 | 1 |
| Cleanup policy | `delete` | `delete` |
| Retention | 2,592,000,000 ms (30 calendar days) | same |
| Automatic topic creation | disabled | disabled |
| Unclean leader election | disabled | disabled |
| Producer acknowledgement | `acks=all` + idempotence | same |
| Production certification | allowed | rejected |

The profile applies independently and exactly to `matching.commands` and `matching.events`. Neither topic is compacted. The record key is not an ownership mechanism: the artifact declares the numeric partition explicitly, and Matching ordinal N owns partition N.

## Provision and verify

For an authenticated production cluster, pass a Kafka CLI command-properties file containing the
approved TLS/SASL settings:

~~~bash
bash scripts/validate-matching-topic-profile.sh \
  --bootstrap-server kafka-1.example:9093,kafka-2.example:9093,kafka-3.example:9093 \
  --command-config /secure/kafka/matching-client.properties \
  --broker-config-file /secure/effective-kafka-broker.properties \
  --profile production \
  --certify-production
~~~

The command-properties file is an external secret and must never be committed. The same
--command-config option is accepted by provision-matching-topics.sh.

The deployment owner must first configure the broker safety properties. Then provision the topics and immediately verify their effective state:

```bash
bash scripts/provision-matching-topics.sh \
  --bootstrap-server kafka-1.example:9092 \
  --profile production \
  --broker-config-file /secure/effective-kafka-broker.properties \
  --certify-production
```

The broker-config file is a read-only effective configuration export when static broker properties are not returned by the Kafka CLI. It must include, at minimum:

```properties
auto.create.topics.enable=false
unclean.leader.election.enable=false
```

The provisioner creates the two topics with explicit partition, replication, cleanup, retention, and minimum-ISR settings, then invokes the fail-closed validator. The validator checks every partition description (including ISR), exact topic configuration, effective broker safety settings, and the repository's producer requirements. It rejects a local profile when `--certify-production` is supplied.

For a no-write review of the generated Kafka CLI calls:

```bash
bash scripts/provision-matching-topics.sh \
  --bootstrap-server kafka:9092 --profile production --certify-production --dry-run
```

The one-broker Compose service intentionally uses the local profile and disables implicit topic creation and unclean election. It is suitable for developer checks only, never production certification.

## Retention and disk certification

Measure the certified workload after compression at the Kafka-record boundary:

```text
logicalBytesPerDay =
    commandsPerDay × averageCommandRecordBytes
  + eventsPerDay   × averageEventRecordBytes

replicatedThirtyDayBytes = logicalBytesPerDay × 30 × 3
requiredUsableClusterBytes = replicatedThirtyDayBytes / 0.70
```

The final division reserves 30% operational headroom for partition skew, index files, broker rebalancing, and normal variance. Production certification must record the measured command/event rates and byte sizes, verify the 30-day result fits the usable cluster capacity, and confirm that no broker's assigned replica set exceeds its local budget. A formula with unmeasured inputs is not a production capacity certification.

Alert immediately on under-replicated partitions, any ISR below 2, topic/profile drift, disabled idempotence or `acks!=all`, automatic topic creation, unclean election, or disk consumption beyond the certified headroom. Future Gateway admission automation consumes those signals in #135; this profile deliberately does not make a local shell script claim to pause live trading.

Run the repository fixture checks with:

```bash
bash scripts/test-matching-topic-profile.sh
```

The tests cover valid production state, insufficient ISR, duplicate replica broker identities,
unsafe automatic topic creation, refusal to certify the local profile, command-config forwarding,
and the generated provisioning commands. A live three-broker run and real workload measurement
remain a deployment-environment gate. See
[Production Live Certification](production-live-certification.md) for the full sequence.

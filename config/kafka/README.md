# Matching Kafka profiles

`matching-production.properties` is the repository-owned production-shaped durability contract for
`matching.commands` and `matching.events`. It requires 15 partitions, replication factor 3,
minimum ISR 2, delete-only cleanup, and 30 calendar days of retention. It also records the
producer requirements: `acks=all` and idempotence. It is used by the local production-like gate;
it is not an external production certification.

`matching-local.properties` is only for the one-broker Compose environment. Its replication factor
and minimum ISR are intentionally reduced to 1, and its `certifies.production=false` value makes it
ineligible for the local RF3 durability gate.

Use `scripts/provision-matching-topics.sh` to create the two topics and
`scripts/validate-matching-topic-profile.sh` to fail closed on profile drift. The production-shaped
The production-shaped Matching profile provisions only the final `matching.commands` and
`matching.events` streams. The retired `matching.executions` stream is not part of this topic
profile or any active deployment.

The validation script requires the effective broker configuration to expose
`auto.create.topics.enable=false` and `unclean.leader.election.enable=false`; the local certification
runner supplies the captured broker configuration, while a later promotion environment can provide
an immutable effective broker configuration file when those settings are static rather than
dynamically queryable.

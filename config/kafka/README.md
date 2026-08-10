# Matching Kafka profiles

`matching-production.properties` is the repository-owned durability contract for
`matching.commands` and `matching.events`. It requires 15 partitions, replication factor 3,
minimum ISR 2, delete-only cleanup, and 30 calendar days of retention. It also records the
producer requirements: `acks=all` and idempotence.

`matching-local.properties` is only for the one-broker Compose environment. Its replication factor
and minimum ISR are intentionally reduced to 1, and its `certifies.production=false` value makes it
ineligible for production certification.

Use `scripts/provision-matching-topics.sh` to create the two topics and
`scripts/validate-matching-topic-profile.sh` to fail closed on profile drift. The validation script
requires the effective broker configuration to expose `auto.create.topics.enable=false` and
`unclean.leader.election.enable=false`; an operator can provide an immutable effective broker
configuration file when those settings are static rather than dynamically queryable.

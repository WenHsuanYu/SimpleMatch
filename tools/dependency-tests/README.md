# Dependency verification

This module owns focused dependency scenarios from the Shell reduction program, [S2 #199](https://github.com/WenHsuanYu/SimpleMatch/issues/199).
JUnit owns scenarios and assertions. Testcontainers supplies disposable infrastructure.
These tests require no Kubernetes cluster.

## PostgreSQL

Run `./gradlew -q verifyPostgres` with Docker, Python 3, and `psql` available.
Local and CI use this same entry.
The entry runs:

- The actual service Flyway configuration and retained operator inventory contracts.
- Missing and wrapper-only runtime-cache seed rejection through the real operational entry.
- The build convention tests, including actual dependency preparation.
- All six owners' real Gradle Flyway info, migrate, repeat-migrate, and validate tasks.
- Service-local histories, required tables, PostgreSQL constraint probes, and eleven index plans.
- Gateway session SQL against the checked-in CHAR/VARCHAR identity baseline.
- The existing Query PostgreSQL JDBC, HTTP, concurrency, and collector contracts.

Each live invocation creates new databases and executes its checks again.
Only the Testcontainers-owned environments receive migrations or fixture writes.
Normal dependency and build caches remain available.
Nested Flyway tasks use an isolated project cache and the prepared, offline dependency cache.

`postgresTest` runs the six-owner database scenario alone.
The ordinary module `test` task runs the inventory and runtime-cache rejection cases without Docker.
The tests retain the versioned migration files and their service-owned Gradle configuration.
The old source-text checks for Dockerfile and wrapper formatting are retired.

## Read-only operators

`scripts/verify-postgres-live-certification.sh` retains its `SIMPLEMATCH_LIVE_POSTGRES_*` environment interface.
It invokes `postgresLiveTest` for primary, TLS, logical WAL, histories, Flyway validation, and query plans.
TLS hostname verification remains required. Connections default to read-only transactions.
It never runs migration or fixture methods.

`scripts/check-flyway-query-plans.sh DATABASE SERVICE` invokes `queryPlanTest` with the existing `CI_POSTGRES_*` connection interface.
Both operators use the same assertions and plan cases as disposable verification.
They do not substitute for deployed application or aggregate certification evidence.

The Gateway SQL compatibility entry delegates to `postgresTest`.
S5-B1 owns its final path removal. It contains no duplicate verdict.

## CDC publication and recovery

Run `./gradlew verifyCdc` with Java 25 and Docker. Local and CI use this same entry.
It runs canonical connector contracts, compiled producer-boundary checks, and the shared Java
CDC verifier regressions. Each fresh RF1 and RF3 environment applies canonical Risk and Account
Flyway migrations and observes actual JDBC, Kafka, and Connect REST interfaces.

The scenarios preserve exact event identity, post-baseline offsets, key and payload bytes,
logical timestamps, explicit partitions, the seven Debezium headers, and retained outbox rows.
They cover historical Account event selection, connector pause/resume, all-broker stop/start,
and a forced Connect process exit before source-offset commit. Redelivery must contain the
same durable event after a newly captured Kafka baseline. No offset reset creates that result.
RF3 checks fifteen Matching partitions, distinct replicas, minimum ISR 2, and disabled unclean election.

Each live task executes again. A run owns its containers and network by a unique run ID.
Kafka and Connect keep run-selected loopback port bindings across actual restart faults.
Reports in `build/reports/dependency-tests/cdc/<run-id>/report.json` retain source revision,
input fingerprint, actual image config IDs and repository digests, and publication digests.
A report becomes `PASS` only after exact container and network removal checks.
JUnit XML remains available in `build/test-results/cdcTest/`.

The `recovery-evidence-verifier cdc` CLI owns the same assertions for retained Kubernetes collectors.
The shared Shell library retains system reads and bounded polling until S5-G migrates its final consumer.
S2-A removes the five assigned deployment/verification scripts. The old source-text producer grep is
replaced by a Java Class-File API guard over actual owner classes, with a rejecting producer fixture.

Run `./gradlew verifyDependencies` for the complete S2 PostgreSQL, CDC, and Query Redis acceptance.
These results establish dependency and transport behavior. Application transactions, full-local
certification, and deployed Kubernetes recovery keep their own acceptance boundaries.

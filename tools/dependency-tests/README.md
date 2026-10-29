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

# Database Implementation Guide

The canonical target database topology and ownership rules are in
[the target platform specification](../../services/docs/platform/database-architecture.md). Cross-
service identity, recovery, and retry semantics are in
[Consistency, Recovery, Identity, and Error Boundaries](../../services/docs/platform/consistency-recovery-identity-and-errors.md).
This guide preserves rollout, migration, connector, and verification evidence for the existing
repository; it is not a second target-architecture source.

## Implementation touchpoints

### Flyway convention and build configuration

| File                                                                                                                         | Implementation responsibility                                              |
|------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| [SimpleMatchFlywayServicePlugin.kt](../../build-logic/src/main/kotlin/com/simplematch/gradle/SimpleMatchFlywayServicePlugin.kt) | Resolves service-scoped schema configuration and Flyway history placement. |
| [risk-service build](../../services/risk-service/build.gradle.kts)                                                              | Declares the `risk_service` owner schema.                                  |
| [account-service build](../../services/account-service/build.gradle.kts)                                                        | Declares the `account_service` owner schema.                               |
| [persistence build](../../services/persistence/build.gradle.kts)                                                                | Declares the `persistence` owner schema.                                   |
| [query-service build](../../services/query-service/build.gradle.kts)                                                            | Declares the `query_service` rebuildable read-model schema.                |

### Migration SQL

| File                                                                                                                                                                 | Implementation evidence                                                                                              |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| [account-service V2](../../services/account-service/src/main/resources/db/migration/account-service/V2__add_account_authority_lifecycle_tables.sql)                     | Extends Account authority tables with lifecycle columns, inbox, and binary outbox.                                   |
| [account-service V6](../../services/account-service/src/main/resources/db/migration/account-service/V6__canonical_account_identity.sql)                                 | Moves Account-owned `account_id` columns to native UUID for the canonical identity contract.                         |
| [account-service V9](../../services/account-service/src/main/resources/db/migration/account-service/V9__persist_reservation_trading_day.sql)                               | Persists the admission business trading day on reservations for historical release/fill authority.                  |
| [risk-service V2](../../services/risk-service/src/main/resources/db/migration/risk-service/V2__add_durable_admission_journal.sql)                                       | Adds the durable Risk admission journal beside decision/outbox state.                                                |
| [risk-service V12](../../services/risk-service/src/main/resources/db/migration/risk-service/V12__add_local_resilience_marker.sql)                                       | Adds a diagnostic-only durable marker separate from observer-owned CDC health data.                                  |
| [persistence V1](../../services/persistence/src/main/resources/db/migration/persistence/V1__create_projection_tables.sql)                                               | Creates typed projection and inbox tables from an empty schema.                                                      |
| [query-service V1](../../services/query-service/src/main/resources/db/migration/query-service/V1__create_query_read_models.sql)                                         | Creates inbox/checkpoints and query-owned order, execution, account, and active-reference models.                    |

The historical Phase 4 field catalog records the schema state delivered at that phase; later
versioned migrations such as Account V6 intentionally supersede those historical column shapes.
Do not rewrite a historical execution record to make it look like the later migration had already
existed.

Migrations must either use schema-qualified object names or set an explicit search path. They must
not rely on implicit `public`.

### Runtime, connectors, and verification

| Area                        | Implementation evidence                                                                                                                                                                             |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Runtime datasource          | Risk and Account persistence select their owner schemas rather than `public`; Query uses the shared Boot adapter with `query_service` pool policy.                                                   |
| Canonical Account identity  | Account JDBC readers/writers bind and rehydrate `java.util.UUID` values matching the Account-domain `AccountId`.                                                                                   |
| Market-reference authority | The offline `tools/market-reference-builder` emits the approved artifact consumed by Risk and Matching; no runtime owner schema or publication outbox exists.                                  |
| Outbox connector            | Compose/Kubernetes connector configuration uses schema-qualified owner outbox tables.                                                                                                               |
| Migration tests             | Service migration tests validate owner schemas, versioned migrations, constraints, and clean-install/idempotent migration behavior.                                                                |
| Flyway CI                   | All registered Flyway services, including Query Service, migrate into one CI database while retaining independent owner schemas and independent `flyway_schema_history`.                         |

## Current schema contract cleanup

The [completed schema cleanup record](../verification/schema-contract-cleanup-record.md) records the no-legacy-data
assumption, service ownership, current symbol/venue rule and validation boundaries. The current
schema uses native UUIDs for Account and Query canonical identities, whole-share quantities and exact TWD
amounts. Account requires an explicit venue and trading day; neither LEGACY venue nor wall-clock
fallbacks are part of the active contract. Account-wide monetary aggregates retain a wider exact
range than an individual v2 reservation's signed-long units.

Later migrations supersede the historical table catalog above. Persistence's obsolete `orders`,
`executions` and `inbox` are retired in favor of `trades`, `order_fills`,
`matching_order_projections` and the Matching Event inbox. Flyway smoke/index checks target these
active tables. Query retains mixed-source text event IDs while its canonical account/order/execution
IDs are native UUIDs. Market-data projection accepts the same 12-character symbol capacity as the
shared instrument contract.

`run-flyway-ci-checks.sh` also executes `check-service-schema-contracts.sql` against PostgreSQL.
These probes first insert valid rows, reject individual invalid values with specific SQLSTATEs,
and roll back every fixture. They complement H2/JDBC tests; they do not certify a live deployment.
The [completed follow-up record](../verification/schema-contract-followup-record.md) documents the existing Query JDBC
contract suite on PostgreSQL, exercising the production ON CONFLICT branch and actual transaction
rollback.
The runner creates a uniquely named scratch database, records ownership, and removes only that
owned database after the explicit JDBC task. The ordinary Java suite keeps its fast H2 coverage.

The [canonical reason revision](../verification/schema-contract-followup-record.md#canonical-reason-storage-revision)
supersedes the Unicode whitespace CHECKs with Account V12 and Risk V15. Rejection codes have a
simple ASCII token grammar, absent Account reasons are exact empty strings, and SQL requires
nonempty rejected detail. Application constructors retain nonblank detail validation. RELEASED
semantics and existing NOT NULL columns remain unchanged.

The [completed hardening record](../verification/schema-contract-hardening-record.md) documents complete
active-reference facts with the verified artifact's strict price band and routing bounds. Query validates
canonical Account UUID event identities before claiming its inbox while retaining Matching SHA-256 text IDs.
Gateway stores a complete serialized FIX SessionID as TEXT and checks configured components against
the existing session-table capacities. Account removes the unused release timestamp while preserving
its created/updated timestamp constraint.

Historical migrations remain versioned; no Flyway repair or database reset is required merely to
apply the new source to an empty database.

## Identity and persistence rule

Database convenience does not define identity ownership. A canonical identifier is defined by its
owning domain and then persisted using a compatible native database representation. Account Service
owns canonical `account_id`; Gateway and Risk only validate or propagate that UUID. Shared database
hosting does not give another service permission to invent, rewrite, or join against Account-owned
identity state.

## Rollout checklist

- Confirm the service schema is declared through the shared Flyway convention.
- Apply versioned migrations through the owning service task.
- Verify runtime datasource selection and connector table inclusion are schema-qualified.
- When an identity representation changes, verify the owning domain type, service boundary, JDBC
  bindings, constraints, and clean-install migration together.
- Run the owning service's migration and integration checks before deployment.
- Keep implementation status, command output, and phase evidence in repository execution material
  rather than the target-specification tree.

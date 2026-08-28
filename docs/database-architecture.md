# Database Architecture Implementation Guide

The canonical target database topology and ownership rules are in
[the target platform specification](../services/docs/platform/database-architecture.md). Cross-
service identity, recovery, and retry semantics are in
[Consistency, Recovery, Identity, and Error Boundaries](../services/docs/platform/consistency-recovery-identity-and-errors.md).
This guide preserves rollout, migration, connector, and verification evidence for the existing
repository; it is not a second target-architecture source.

## Implementation touchpoints

### Flyway convention and build configuration

| File                                                                                                                         | Implementation responsibility                                              |
|------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| [SimpleMatchFlywayServicePlugin.kt](../build-logic/src/main/kotlin/com/simplematch/gradle/SimpleMatchFlywayServicePlugin.kt) | Resolves service-scoped schema configuration and Flyway history placement. |
| [risk-service build](../services/risk-service/build.gradle.kts)                                                              | Declares the `risk_service` owner schema.                                  |
| [account-service build](../services/account-service/build.gradle.kts)                                                        | Declares the `account_service` owner schema.                               |
| [persistence build](../services/persistence/build.gradle.kts)                                                                | Declares the `persistence` owner schema.                                   |
| [marketdata-publisher build](../services/marketdata-publisher/build.gradle.kts)                                              | Declares the `marketdata_publisher` owner schema.                          |
| [query-service build](../services/query-service/build.gradle.kts)                                                            | Declares the `query_service` rebuildable read-model schema.                |

### Migration SQL

| File                                                                                                                                                                 | Implementation evidence                                                                                              |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| [account-service V2](../services/account-service/src/main/resources/db/migration/account-service/V2__add_account_authority_lifecycle_tables.sql)                     | Extends Account authority tables with lifecycle columns, inbox, and binary outbox.                                   |
| [account-service V6](../services/account-service/src/main/resources/db/migration/account-service/V6__canonical_account_identity.sql)                                 | Moves Account-owned `account_id` columns to native UUID for the canonical identity contract.                         |
| [account-service V9](../services/account-service/src/main/resources/db/migration/account-service/V9__persist_reservation_trading_day.sql)                               | Persists the admission business trading day on reservations for historical release/fill authority.                  |
| [risk-service V2](../services/risk-service/src/main/resources/db/migration/risk-service/V2__add_durable_admission_journal.sql)                                       | Adds the durable Risk admission journal beside decision/outbox state.                                                |
| [persistence V1](../services/persistence/src/main/resources/db/migration/persistence/V1__create_projection_tables.sql)                                               | Creates typed projection and inbox tables from an empty schema.                                                      |
| [marketdata-publisher V1](../services/marketdata-publisher/src/main/resources/db/migration/marketdata-publisher/V1__create_marketdata_publisher_tables.sql)          | Creates immutable market snapshots and the transactional publication outbox.                                        |
| [query-service V1](../services/query-service/src/main/resources/db/migration/query-service/V1__create_query_read_models.sql)                                         | Creates inbox/checkpoints and query-owned order, execution, account, and active-reference models.                    |

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
| Market-reference datasource | `MarketdataPublisherConfiguration` selects the `marketdata_publisher` schema.                                                                                                                      |
| Outbox connector            | Compose/Kubernetes connector configuration uses schema-qualified owner outbox tables.                                                                                                               |
| Migration tests             | Service migration tests validate owner schemas, versioned migrations, constraints, and clean-install/idempotent migration behavior.                                                                |
| Flyway CI                   | All registered Flyway services, including Query Service, migrate into one CI database while retaining independent owner schemas and independent `flyway_schema_history`.                         |

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

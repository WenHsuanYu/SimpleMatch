# Database Architecture Implementation Guide

The canonical target database topology and ownership rules are in
[the target platform specification](../services/docs/platform/database-architecture.md). This guide preserves rollout,
migration, connector, and verification evidence for the existing repository; it is not a second target-architecture
source.

## Implementation touchpoints

### Flyway convention and build configuration

| File                                                                                                                         | Implementation responsibility                                              |
|------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| [SimpleMatchFlywayServicePlugin.kt](../build-logic/src/main/kotlin/com/simplematch/gradle/SimpleMatchFlywayServicePlugin.kt) | Resolves service-scoped schema configuration and Flyway history placement. |
| [risk-service build](../services/risk-service/build.gradle.kts)                                                              | Declares the `risk_service` owner schema.                                  |
| [account-service build](../services/account-service/build.gradle.kts)                                                        | Declares the `account_service` owner schema.                               |
| [persistence build](../services/persistence/build.gradle.kts)                                                                | Declares the `persistence` owner schema.                                   |

### Migration SQL

| File                                                                                                                                                        | Implementation evidence                                                                                            |
|-------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| [account-service V2](../services/account-service/src/main/resources/db/migration/account-service/V2__add_account_authority_lifecycle_tables.sql)            | Extends the typed `account_service` authority tables with lifecycle columns, inbox, and binary outbox.             |
| [risk-service V2](../services/risk-service/src/main/resources/db/migration/risk-service/V2__add_durable_admission_journal.sql)                              | Adds the durable `risk_service` admission journal beside the decision and binary outbox tables.                    |
| [persistence V1](../services/persistence/src/main/resources/db/migration/persistence/V1__create_projection_tables.sql)                                      | Creates the typed `persistence` projection and inbox tables from an empty database.                                |
| [marketdata-publisher V1](../services/marketdata-publisher/src/main/resources/db/migration/marketdata-publisher/V1__create_marketdata_publisher_tables.sql) | Creates immutable market snapshots, one active snapshot per trading day, and the transactional publication outbox. |

The reset's field meaning, range, nullability, constraints, and index review are recorded in
the [Phase 4 data dictionary](phase-4-data-dictionary.md). The previous chains remain recoverable at the
`phase-4-pre-flyway-reset` tag; ordinary migration tasks use `baselineOnMigrate = false`.

Migrations must either use schema-qualified object names or set an explicit search path. They must not rely on implicit
`public`.

### Runtime, connectors, and verification

| Area                        | Implementation evidence                                                                                                                                                                                             |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Runtime datasource          | [RiskServiceConfiguration.java](../services/risk-service/src/main/java/com/simplematch/riskservice/config/RiskServiceConfiguration.java) selects the `risk_service` schema.                                         |
| Market-reference datasource | [MarketdataPublisherConfiguration.java](../services/marketdata-publisher/src/main/java/com/simplematch/marketdatapublisher/config/MarketdataPublisherConfiguration.java) selects the `marketdata_publisher` schema. |
| Outbox connector            | [Compose connector](../deploy/compose/risk-service-outbox-connector.json) and [Kubernetes connector](../deploy/k8s/risk-service-outbox-connector-configmap.yaml) use the schema-qualified outbox table.             |
| Migration tests             | The risk, account, and persistence service migration tests validate their owner schemas.                                                                                                                            |

## Rollout checklist

- Confirm the service schema is declared through the shared Flyway convention.
- Apply versioned migrations through the owning service task.
- Verify runtime datasource selection and connector table inclusion are schema-qualified.
- Run the owning service's migration and integration checks before deployment.
- Keep implementation status, command output, and phase evidence in repository execution material rather than the
  target-specification tree.

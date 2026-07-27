# Database Architecture Implementation Guide

The canonical target database topology and ownership rules are in
[the target platform specification](../services/docs/platform/database-architecture.md).
This guide preserves rollout, migration, connector, and verification evidence
for the existing repository; it is not a second target-architecture source.

## Implementation touchpoints

### Flyway convention and build configuration

| File                                                                                                                         | Implementation responsibility                                              |
|------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| [SimpleMatchFlywayServicePlugin.kt](../build-logic/src/main/kotlin/com/simplematch/gradle/SimpleMatchFlywayServicePlugin.kt) | Resolves service-scoped schema configuration and Flyway history placement. |
| [risk-service build](../services/risk-service/build.gradle.kts)                                                              | Declares the `risk_service` owner schema.                                  |
| [account-service build](../services/account-service/build.gradle.kts)                                                        | Declares the `account_service` owner schema.                               |
| [persistence build](../services/persistence/build.gradle.kts)                                                                | Declares the `persistence` owner schema.                                   |

### Migration SQL

| File                                                                                                                                    | Implementation evidence                                                                                                          |
|-----------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| [risk-service V1](../services/risk-service/src/main/resources/db/migration/risk-service/V1__create_risk_service_tables.sql)             | Creates the `risk_service` schema and its initial tables.                                                                        |
| [risk-service V2](../services/risk-service/src/main/resources/db/migration/risk-service/V2__drop_legacy_outbox_relay_columns.sql)       | Uses schema-qualified object changes.                                                                                            |
| [risk-service V3](../services/risk-service/src/main/resources/db/migration/risk-service/V3__add_outbox_kafka_partition_id.sql)          | Adds partition routing to `risk_service.outbox`.                                                                                 |
| risk-service V9 UUID migration                                                                                                          | Converts `event_id` and `outbox_event_id` to PostgreSQL `UUID` while the outer synchronous contract retains string `request_id`. |
| [account-service V1](../services/account-service/src/main/resources/db/migration/account-service/V1__create_account_service_tables.sql) | Creates the `account_service` schema and tables.                                                                                 |
| [persistence V1](../services/persistence/src/main/resources/db/migration/persistence/V1__create_projection_tables.sql)                  | Creates the `persistence` schema and projection tables.                                                                          |

Migrations must either use schema-qualified object names or set an explicit
search path. They must not rely on implicit `public`.

### Runtime, connectors, and verification

| Area               | Implementation evidence                                                                                                                                                                                 |
|--------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Runtime datasource | [RiskServiceConfiguration.java](../services/risk-service/src/main/java/com/simplematch/riskservice/config/RiskServiceConfiguration.java) selects the `risk_service` schema.                             |
| Outbox connector   | [Compose connector](../deploy/compose/risk-service-outbox-connector.json) and [Kubernetes connector](../deploy/k8s/risk-service-outbox-connector-configmap.yaml) use the schema-qualified outbox table. |
| Migration tests    | The risk, account, and persistence service migration tests validate their owner schemas.                                                                                                                |

## Rollout checklist

- Confirm the service schema is declared through the shared Flyway convention.
- Apply versioned migrations through the owning service task.
- Verify runtime datasource selection and connector table inclusion are
  schema-qualified.
- Run the owning service's migration and integration checks before deployment.
- Keep implementation status, command output, and phase evidence in repository
  execution material rather than the target-specification tree.
